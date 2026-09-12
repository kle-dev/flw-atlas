package com.flowable.atlas.index

import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.events.AtlasEventsListener
import com.flowable.atlas.model.JsonUtil
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.parsing.DataObjectInfo
import com.flowable.atlas.parsing.ModelMemberExtractor
import com.flowable.atlas.parsing.ModelMembers
import com.flowable.atlas.parsing.ModelRefScanner
import com.flowable.atlas.parsing.OperationInfo
import com.flowable.atlas.parsing.RestCallScanner
import com.flowable.atlas.parsing.ParamInfo
import com.flowable.atlas.parsing.ServiceTable
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Project-wide index of Flowable model keys and the query facade used by the completion
 * contributors. Backed by a cached full scan of the project's content roots; invalidated
 * when any model file changes. The public API (keysOfType / find / operationsOf /
 * inputParametersOf) is intentionally storage-agnostic so the backing store can later be
 * swapped for a FileBasedIndex without touching callers.
 */
@Service(Service.Level.PROJECT)
class FlowableModelIndexService(private val project: Project) : Disposable {

    private val LOG = logger<FlowableModelIndexService>()

    @Volatile
    private var cached: FlowableIndex? = null

    /** Why the last build failed, until a model changes or a rebuild is asked for. A failed build used to
     *  leave nothing behind: the Hub read "no index" as "not built yet", asked again on every refresh and
     *  started a fresh doomed scan each time, saying *scanning…* forever and logging nothing. */
    @Volatile
    private var lastFailure: Throwable? = null

    /** data-object key → physical table name; derived from [cached] and dropped with it. */
    @Volatile
    private var dataObjectTablesCache: Map<String, String>? = null

    // Per-snapshot memos of what the JSON model files say, dropped with [cached]. The inspections ask
    // for these per literal on every highlighting pass — a DAO with twenty query builders re-read and
    // re-parsed the same `.service` model twenty times, and the Liquibase coverage inspection parsed
    // every `.service` in the project for every changelog it looked at.
    @Volatile
    private var serviceTablesCache: List<ServiceTable>? = null
    private val backingServiceKeyCache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<String>>()
    private val operationsCache = java.util.concurrent.ConcurrentHashMap<String, List<OperationInfo>>()

    /** The one background build in flight, if any — every [ensureBuilding] while it runs joins it. */
    private val inFlight = AtomicReference<CompletableFuture<FlowableIndex>?>()

    /** Bumped on every invalidation, so a build that started before the change cannot cache its stale
     *  snapshot over a newer one (a pull rewrites the archives, the VFS invalidates, a rebuild runs —
     *  and an older scan that is still finishing must lose). */
    private val generation = AtomicLong()

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    // `path` is the new one: a rename away from a model extension (`x.bpmn` → `x.bpmn.bak`)
                    // or a move out of scope matched nothing, and the key stayed indexed until a Rebuild.
                    if (events.any { touchesModel(it) }) {
                        drop()
                        publishUpdated()
                    }
                }
            },
        )
    }

    private fun touchesModel(e: VFileEvent): Boolean =
        ModelFiles.isModelPath(e.path) ||
            (e as? VFilePropertyChangeEvent)?.takeIf { it.propertyName == VirtualFile.PROP_NAME }?.oldPath?.let(ModelFiles::isModelPath) == true ||
            (e as? VFileMoveEvent)?.oldPath?.let(ModelFiles::isModelPath) == true

    private fun drop() {
        generation.incrementAndGet()
        cached = null; lastFailure = null; dataObjectTablesCache = null
        serviceTablesCache = null; backingServiceKeyCache.clear(); operationsCache.clear()
    }

    /** The reason the last build failed, or null — a failed index is a state the Hub has to show. */
    fun lastFailureOrNull(): Throwable? = lastFailure

    /**
     * The current index, building it on first use / after invalidation.
     *
     * **Who may call this.** Only a context that is allowed to wait: completion (cancellable), a
     * `Task.Backgroundable`, a Design pull, the generators, the Rebuild action. A daemon pass, a
     * reference resolve or a line-marker provider must read [cachedOrNull] and call [ensureBuilding]
     * instead — those run under the read lock, and a multi-second scan under the read lock is what
     * "the IDE freezes while I type" looks like.
     *
     * Inside a read action the build runs inline rather than waiting for another thread's: that
     * thread needs the read lock too, and with a write action pending the wait would deadlock. The
     * phase-1/phase-2 split in [buildAndCache] keeps the lock held for milliseconds either way.
     */
    fun index(): FlowableIndex {
        cached?.let { return it }
        // A torn scan during shutdown is worthless — and iterating a disposing VFS spams the log.
        if (project.isDisposed) return build(emptyList())
        if (ApplicationManager.getApplication().isReadAccessAllowed) return buildAndCache()
        return join(startOrJoin())
    }

    /**
     * Wait for a build on another thread, honouring the caller's own cancellation: Find Usages' Cancel
     * used to have no effect on the scan, and a cancelled build came back as an `ExecutionException` —
     * PCE identity lost, reported to the user as an error.
     */
    private fun join(future: CompletableFuture<FlowableIndex>): FlowableIndex {
        while (true) {
            ProgressManager.checkCanceled()
            try {
                return future.get(50, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                continue
            } catch (e: ExecutionException) {
                val cause = e.cause ?: e
                if (cause is ProcessCanceledException) throw cause
                throw cause
            }
        }
    }

    /**
     * Make sure an index exists or is being built, without waiting. The one call every read-context
     * consumer makes when [cachedOrNull] is empty: one background build for any number of callers, and
     * when it lands the daemon restarts so markers, hints and inspections appear without retyping.
     */
    fun ensureBuilding() {
        if (cached != null || project.isDisposed) return
        // A light test runs highlighting synchronously on the EDT and asserts on its result at once; a
        // build that lands later would make every inspection test a race. Inline there — the production
        // path is [ensureBuildingAsync], and ModelIndexEnsureBuildingTest exercises it on purpose.
        if (ApplicationManager.getApplication().isUnitTestMode) { buildAndCache(); return }
        ensureBuildingAsync()
    }

    /** The background half of [ensureBuilding]: one pooled build, joined by every caller until it lands. */
    internal fun ensureBuildingAsync() {
        // A build that failed is not retried on every ask — a model change or an explicit Rebuild clears
        // the failure (drop) and the next ask builds again.
        if (cached != null || project.isDisposed || lastFailure != null) return
        startOrJoin()
    }

    /**
     * What a read-context consumer calls: the cached index, or — after asking for a background build —
     * null, meaning "no verdict this pass; the daemon restarts when the index lands and asks again".
     */
    fun cachedOrRequest(): FlowableIndex? {
        cached?.let { return it }
        ensureBuilding()
        return cached
    }

    private fun startOrJoin(): CompletableFuture<FlowableIndex> {
        while (true) {
            inFlight.get()?.let { return it }
            val future = CompletableFuture<FlowableIndex>()
            if (inFlight.compareAndSet(null, future)) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        future.complete(buildAndCache())
                    } catch (pce: ProcessCanceledException) {
                        future.completeExceptionally(pce)          // a cancel, not a failure: nothing to record
                    } catch (t: Throwable) {
                        LOG.warn("The Flowable model index could not be built", t)
                        lastFailure = t
                        future.completeExceptionally(t)
                        publishUpdated()                           // the Hub shows the failure instead of scanning forever
                    } finally {
                        inFlight.compareAndSet(future, null)
                    }
                }
                return future
            }
        }
    }

    /**
     * Phase 1, under a (short) read action: only COLLECT the candidate files — no file content is read
     * while the lock is held. Phase 2 (bytes + parse + regex) runs lock-free, so a pending write action
     * (typing, VFS refresh) never queues behind a multi-second scan. One long read action here used to
     * freeze the EDT for seconds on large workspaces.
     */
    private fun buildAndCache(): FlowableIndex {
        val gen = generation.get()
        val candidates = ReadAction.computeBlocking<List<VirtualFile>, RuntimeException> { collectCandidates() }
        val built = build(candidates)
        if (project.isDisposed) return built           // don't cache what a dying scan produced
        if (generation.get() != gen) return built      // invalidated meanwhile — a newer build owns the cache
        cached = built
        publishUpdated()
        restartDaemon()
        return built
    }

    /** A build landing means markers and hints that were skipped on a cold index can now be drawn. */
    private fun restartDaemon() {
        // A light test pumps the event queue *during* highlighting, where the platform forbids a daemon
        // restart; tests that need one call it themselves.
        if (ApplicationManager.getApplication().isUnitTestMode) return
        ApplicationManager.getApplication().invokeLater(
            { if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart("Flowable model index built") },
            project.disposed,
        )
    }

    /**
     * The cached index if one exists, without triggering a (blocking) build. The Atlas Hub's
     * status display uses this — the full scan must never run on the EDT.
     */
    fun cachedOrNull(): FlowableIndex? = cached

    /** Force a rebuild and return the fresh index. Deliberately does not join a build already in
     *  flight — it may have started before the files this rebuild is about were written. */
    fun refresh(): FlowableIndex {
        drop()
        return buildAndCache()
    }

    /** Drop the cached index so it is rebuilt lazily on next use (cheap; safe on the EDT). */
    fun invalidate() {
        drop()
        publishUpdated()
    }

    /** May fire from any thread (VFS events, completion-triggered builds) — see [AtlasEventsListener]. */
    private fun publishUpdated() {
        if (!project.isDisposed) {
            project.messageBus.syncPublisher(AtlasEvents.TOPIC).modelIndexUpdated()
        }
    }

    fun keysOfType(type: ModelType): List<ModelEntry> = index().keysOfType(type)

    fun find(key: String): List<ModelEntry> = index().find(key)

    /** Distinct `.action` models that invoke the bot with this [botKey]. */
    fun actionsUsingBot(botKey: String): List<ModelEntry> = index().actionsUsingBot(botKey)

    /**
     * The backing service model's key for a data object (its `referencedServiceDefinitionModelKey`),
     * or null when the data object / that field is absent. The operation + value-field catalog is
     * declared on this service model — see [operationsOf].
     */
    fun backingServiceKey(dataObjectKey: String): String? = backingServiceKey(dataObjectKey, index())

    /** [backingServiceKey] against the cached index only — for a highlighting pass, which must never build. */
    fun cachedBackingServiceKey(dataObjectKey: String): String? = cachedOrNull()?.let { backingServiceKey(dataObjectKey, it) }

    private fun backingServiceKey(dataObjectKey: String, idx: FlowableIndex): String? {
        backingServiceKeyCache[dataObjectKey]?.let { return it.orElse(null) }
        // The generation is read before the computation and checked after it: a memo written after a
        // drop() would otherwise carry a pre-pull answer past the invalidation until the next change.
        val gen = generation.get()
        val dataFile = idx.find(dataObjectKey, ModelType.DATA_OBJECT)?.file ?: return null
        val key = ReadAction.computeBlocking<String?, RuntimeException> {
            JsonUtil.topLevelString(dataFile, "referencedServiceDefinitionModelKey")
        }
        if (generation.get() == gen) backingServiceKeyCache[dataObjectKey] = java.util.Optional.ofNullable(key)
        return key
    }

    /** Operations available on a data object, resolved via its backing service model. */
    fun operationsOf(dataObjectKey: String): List<OperationInfo> {
        val serviceKey = backingServiceKey(dataObjectKey) ?: return emptyList()
        return operationsOfService(serviceKey)
    }

    /** [operationsOf] against the cached index only; empty when there is none. */
    fun cachedOperationsOf(dataObjectKey: String): List<OperationInfo> {
        val idx = cachedOrNull() ?: return emptyList()
        val serviceKey = backingServiceKey(dataObjectKey, idx) ?: return emptyList()
        return operationsOfService(serviceKey, idx)
    }

    /** Operations declared directly on a service model. */
    fun operationsOfService(serviceKey: String): List<OperationInfo> = operationsOfService(serviceKey, index())

    /** [operationsOfService] against the cached index only; empty when there is none. */
    fun cachedOperationsOfService(serviceKey: String): List<OperationInfo> =
        cachedOrNull()?.let { operationsOfService(serviceKey, it) } ?: emptyList()

    private fun operationsOfService(serviceKey: String, idx: FlowableIndex): List<OperationInfo> {
        operationsCache[serviceKey]?.let { return it }
        val gen = generation.get()
        val serviceFile = idx.find(serviceKey, ModelType.SERVICE)?.file ?: return emptyList()
        val ops = ReadAction.computeBlocking<List<OperationInfo>, RuntimeException> {
            JsonUtil.readOperations(serviceFile)
        }
        if (generation.get() == gen) operationsCache[serviceKey] = ops
        return ops
    }

    /** Input value fields required by a data object's operation. */
    fun inputParametersOf(dataObjectKey: String, operationKey: String): List<ParamInfo> =
        operationsOf(dataObjectKey).firstOrNull { it.key == operationKey }?.inputParameters.orEmpty()

    // ---- member vocabularies (non-key completion domains) ------------------------------

    /** Project-wide process/case variable names. */
    fun variables(): Set<String> = index().variables

    /** BPMN message names (for startProcessInstanceByMessage / messageEventReceived). */
    fun messages(): Set<String> = index().messages

    /** BPMN signal names (for signalEventReceived). */
    fun signals(): Set<String> = index().signals

    /** userTask ids (for taskDefinitionKey). */
    fun userTaskIds(): Set<String> = index().userTaskIds

    /** Flow-node ids (for activityId). */
    fun activityIds(): Set<String> = index().activityIds

    /** DMN input/output variable names of a decision (for ExecuteDecisionBuilder.variable). */
    fun decisionVariablesOf(decisionKey: String): List<String> =
        index().membersOf(decisionKey, ModelType.DECISION)?.decisionVariables.orEmpty()

    /** Members of a single model resolved by [key], trying each of [types] in turn (first hit wins). */
    fun scopedMembers(key: String, types: List<ModelType>): ModelMembers? =
        types.firstNotNullOfOrNull { index().membersOf(key, it) }

    /** Payload + correlation parameter names of an event (for event-payload completion). */
    fun payloadOf(eventKey: String): List<String> =
        index().membersOf(eventKey, ModelType.EVENT)?.payload.orEmpty()

    /** Project-wide form outcome values (for completeTaskWithForm's outcome argument). */
    fun formOutcomes(): Set<String> {
        val out = LinkedHashSet<String>()
        for (type in listOf(ModelType.FORM, ModelType.PAGE)) {
            for (e in index().keysOfType(type)) out.addAll(e.members.formOutcomes)
        }
        return out
    }

    // ---- Liquibase-coverage support (read on demand) -----------------------------------

    /** The physical-table mapping of a `.service` model, or null if not a database service / not found. */
    fun serviceTableOf(serviceKey: String): ServiceTable? = serviceTableOf(serviceKey, index())

    /** [serviceTableOf] against the cached index only — hover and highlighting must never build. */
    fun cachedServiceTableOf(serviceKey: String): ServiceTable? = cachedOrNull()?.let { serviceTableOf(serviceKey, it) }

    private fun serviceTableOf(serviceKey: String, idx: FlowableIndex): ServiceTable? {
        val file = idx.find(serviceKey, ModelType.SERVICE)?.file ?: return null
        return ReadAction.computeBlocking<ServiceTable?, RuntimeException> { JsonUtil.readServiceTable(file) }
    }

    /** The logical field mapping of a `.data` model, or null if not found. */
    fun dataObjectInfoOf(dataObjectKey: String): DataObjectInfo? = dataObjectInfoOf(dataObjectKey, index())

    /** [dataObjectInfoOf] against the cached index only. */
    fun cachedDataObjectInfoOf(dataObjectKey: String): DataObjectInfo? = cachedOrNull()?.let { dataObjectInfoOf(dataObjectKey, it) }

    private fun dataObjectInfoOf(dataObjectKey: String, idx: FlowableIndex): DataObjectInfo? {
        val file = idx.find(dataObjectKey, ModelType.DATA_OBJECT)?.file ?: return null
        return ReadAction.computeBlocking<DataObjectInfo?, RuntimeException> { JsonUtil.readDataObject(file) }
    }

    /**
     * The field mapping of a `.masterdata` model (for MasterDataInstanceQuery's field-filter
     * completion). Same JSON shape/reader as a data object's `fieldMappings` — a `.masterdata`
     * export keeps its fields in a top-level `variables` map, already handled by
     * [JsonUtil.readDataObject] — but it is indexed under [ModelType.MASTER_DATA], not
     * [ModelType.DATA_OBJECT], so it needs its own lookup.
     */
    fun masterDataInfoOf(masterDataKey: String): DataObjectInfo? {
        val file = index().find(masterDataKey, ModelType.MASTER_DATA)?.file ?: return null
        return ReadAction.computeBlocking<DataObjectInfo?, RuntimeException> { JsonUtil.readDataObject(file) }
    }

    /**
     * Every data-object key → its physical table name (via the backing `database` service model:
     * the data object's `referencedServiceDefinitionModelKey`, or a service whose `referenceKey` is
     * the data-object key). Cached and dropped with the index, because inlay hints query it per
     * literal on every pass. Uses the already-built index only ([cachedOrNull]) — never triggers a
     * (blocking) build — so it is cheap to call from a highlighting/hint pass; empty until the index
     * exists. Callers must hold read access (JSON model files are read directly).
     */
    fun dataObjectTables(): Map<String, String> {
        dataObjectTablesCache?.let { return it }
        val gen = generation.get()
        val idx = cachedOrNull() ?: return emptyMap()
        val services = idx.keysOfType(ModelType.SERVICE).mapNotNull { JsonUtil.readServiceTable(it.file) }
        val byKey = services.associateBy { it.key }
        val byRef = services.filter { !it.referenceKey.isNullOrBlank() }.associateBy { it.referenceKey!! }
        val map = LinkedHashMap<String, String>()
        for (entry in idx.keysOfType(ModelType.DATA_OBJECT)) {
            val info = JsonUtil.readDataObject(entry.file) ?: continue
            val table = (info.referencedServiceDefinitionModelKey?.let { byKey[it] } ?: byRef[entry.key])?.tableName
            if (!table.isNullOrBlank()) map[entry.key] = table
        }
        if (generation.get() == gen) dataObjectTablesCache = map
        return map
    }

    /**
     * All indexed database `.service` models (for the Liquibase-coverage inspection and the column
     * completion). Against the cached index only — the callers run under the daemon's read lock, and
     * `index()` inside a read action builds *inline*, which put the whole lock-free phase-2 scan under
     * the lock the split exists to keep it out of. Empty until an index exists; one is asked for.
     * Memoised per index snapshot: the inspection asks for every changelog it highlights.
     */
    fun allServiceTables(cachedOnly: Boolean = false): List<ServiceTable> {
        serviceTablesCache?.let { return it }
        val gen = generation.get()
        // completion may wait for a build (it is cancellable); a highlighting pass may not
        val idx = if (cachedOnly) cachedOrNull() ?: run { ensureBuilding(); return emptyList() } else index()
        val tables = ReadAction.computeBlocking<List<ServiceTable>, RuntimeException> {
            idx.keysOfType(ModelType.SERVICE).mapNotNull { JsonUtil.readServiceTable(it.file) }
        }
        if (generation.get() == gen) serviceTablesCache = tables
        return tables
    }

    /** All indexed `.data` models. */
    fun allDataObjects(): List<DataObjectInfo> = ReadAction.computeBlocking<List<DataObjectInfo>, RuntimeException> {
        index().keysOfType(ModelType.DATA_OBJECT).mapNotNull { JsonUtil.readDataObject(it.file) }
    }

    override fun dispose() {
        cached = null
    }

    // ---- scanning ----------------------------------------------------------------------

    /**
     * Phase 1 — the files worth indexing (model files + archives), collected under the caller's
     * read action. Deliberately does NOT touch file contents: this is the only part of the scan
     * that needs the read lock, so it must stay milliseconds-cheap.
     */
    private fun collectCandidates(): List<VirtualFile> {
        val out = ArrayList<VirtualFile>()
        // The scope — the active sub-project's subtree, else the content roots — is ProjectModelScope's
        // to define, so the Search Everywhere scan and Find Usages into models walk the same files.
        ProjectModelScope.iterateFiles(project) { file ->
            if (!file.isDirectory && !ModelFiles.isExcluded(file.path) &&
                (ModelFiles.typeOf(file) != null || ArchiveModelScanner.isArchive(file))
            ) out.add(file)
            true
        }
        return out
    }

    /** Phase 2 — parse + regex over the candidates' bytes. Runs WITHOUT the read lock. */
    private fun build(candidates: List<VirtualFile>): FlowableIndex {
        val byKey = HashMap<String, MutableList<ModelEntry>>()
        val referencedIdentifiers = HashSet<String>()
        val referencedClassFqns = HashSet<String>()
        val variables = HashSet<String>()
        val messages = HashSet<String>()
        val signals = HashSet<String>()
        val userTaskIds = HashSet<String>()
        val activityIds = HashSet<String>()
        val restCalls = HashSet<RestCallScanner.RestRef>()
        // Archives Atlas could not look into. Logged at debug before, which made "the only .bar in the
        // repository is unreadable" indistinguishable from "this project has no models".
        val skippedArchives = ArrayList<String>()
        // Index one model's content, associating its entry with [navFile] for navigation
        // (a loose file, or a navigable entry inside a .bar/.zip archive).
        fun processModel(fileName: String, bytes: ByteArray, type: ModelType, navFile: VirtualFile) {
            try {
                for (raw in ModelMemberExtractor.extract(fileName, bytes, type)) {
                    val entry = ModelEntry(raw.key, raw.name ?: raw.key, type, navFile, raw.members)
                    byKey.getOrPut(raw.key) { ArrayList() }.add(entry)
                    raw.members.let { m ->
                        variables.addAll(m.variables)
                        messages.addAll(m.messages)
                        signals.addAll(m.signals)
                        userTaskIds.addAll(m.userTaskIds)
                        activityIds.addAll(m.activityIds)
                    }
                }
                val text = String(bytes, Charsets.UTF_8)
                ModelRefScanner.scan(text, referencedIdentifiers, referencedClassFqns)
                restCalls.addAll(RestCallScanner.refs(text))
            } catch (pce: ProcessCanceledException) {
                throw pce                      // a cancelled action is not a failure
            } catch (e: Exception) {
                // unreadable / not valid — skip this model, but leave a trace: a systematically
                // mis-parsed model type would otherwise silently never be indexed
                LOG.debug("skipping unindexable model $fileName", e)
            }
        }

        for (file in candidates) {
            ProgressManager.checkCanceled()       // let a long scan be interrupted (e.g. during completion)
            if (project.isDisposed) break         // shutdown mid-scan — stop before the VFS goes away
            val type = ModelFiles.typeOf(file)
            when {
                type != null ->
                    runCatching { file.contentsToByteArray() }.getOrNull()
                        ?.let { processModel(file.name, it, type, file) }
                // Look inside .bar/.zip archives (real-world deployment; unpacked folder optional).
                ArchiveModelScanner.isArchive(file) -> {
                    // The build may run inline under a read lock (completion on a cold index): no
                    // synchronous jar-FS refresh there. And a cancelled scan is not an unreadable
                    // archive — `runCatching` used to swallow the cancellation and light the Hub's
                    // "archives could not be read" line for it.
                    val opened = try {
                        ArchiveModelScanner.scan(
                            file, allowRefresh = !ApplicationManager.getApplication().isReadAccessAllowed,
                        ) { name, bytes, entryType, entryFile -> processModel(name, bytes, entryType, entryFile) }
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (e: Exception) {
                        LOG.debug("skipping unreadable archive ${file.name}", e); false
                    }
                    if (!opened) skippedArchives.add(file.name)
                }
            }
        }
        return FlowableIndex(
            byKey, referencedIdentifiers, referencedClassFqns,
            variables = variables, messages = messages, signals = signals,
            userTaskIds = userTaskIds, activityIds = activityIds,
            restCalls = restCalls,
            builtAtMillis = System.currentTimeMillis(),
            // `timeStamp` is a cached VFS attribute — no I/O — and the candidates were visited anyway.
            newestModelMtime = candidates.maxOfOrNull { it.timeStamp } ?: 0L,
            skippedArchives = skippedArchives.sorted(),
        )
    }

    /** When a model or archive in scope was last modified, per the cached index; null before a build. */
    fun newestModelMtimeOrNull(): Long? = cachedOrNull()?.newestModelMtime?.takeIf { it > 0 }
}
