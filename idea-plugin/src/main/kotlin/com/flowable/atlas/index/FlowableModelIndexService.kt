package com.flowable.atlas.index

import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.events.AtlasEventsListener
import com.flowable.atlas.model.JsonUtil
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelPaths
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.project.AtlasProjectRootService
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
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
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
                    if (events.any { ModelFiles.isModelPath(it.path) }) {
                        drop()
                        publishUpdated()
                    }
                }
            },
        )
    }

    private fun drop() {
        generation.incrementAndGet()
        cached = null; dataObjectTablesCache = null
        serviceTablesCache = null; backingServiceKeyCache.clear(); operationsCache.clear()
    }

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
        return startOrJoin().get()
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
        if (cached != null || project.isDisposed) return
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
                    } catch (t: Throwable) {
                        future.completeExceptionally(t)
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
    fun backingServiceKey(dataObjectKey: String): String? {
        backingServiceKeyCache[dataObjectKey]?.let { return it.orElse(null) }
        val dataFile = index().find(dataObjectKey, ModelType.DATA_OBJECT)?.file ?: return null
        val key = ReadAction.computeBlocking<String?, RuntimeException> {
            JsonUtil.topLevelString(dataFile, "referencedServiceDefinitionModelKey")
        }
        backingServiceKeyCache[dataObjectKey] = java.util.Optional.ofNullable(key)
        return key
    }

    /** Operations available on a data object, resolved via its backing service model. */
    fun operationsOf(dataObjectKey: String): List<OperationInfo> {
        val serviceKey = backingServiceKey(dataObjectKey) ?: return emptyList()
        return operationsOfService(serviceKey)
    }

    /** Operations declared directly on a service model. */
    fun operationsOfService(serviceKey: String): List<OperationInfo> {
        operationsCache[serviceKey]?.let { return it }
        val serviceFile = index().find(serviceKey, ModelType.SERVICE)?.file ?: return emptyList()
        val ops = ReadAction.computeBlocking<List<OperationInfo>, RuntimeException> {
            JsonUtil.readOperations(serviceFile)
        }
        operationsCache[serviceKey] = ops
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
    fun serviceTableOf(serviceKey: String): ServiceTable? {
        val file = index().find(serviceKey, ModelType.SERVICE)?.file ?: return null
        return ReadAction.computeBlocking<ServiceTable?, RuntimeException> { JsonUtil.readServiceTable(file) }
    }

    /** The logical field mapping of a `.data` model, or null if not found. */
    fun dataObjectInfoOf(dataObjectKey: String): DataObjectInfo? {
        val file = index().find(dataObjectKey, ModelType.DATA_OBJECT)?.file ?: return null
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
        dataObjectTablesCache = map
        return map
    }

    /** All indexed database `.service` models (for the Liquibase-coverage inspection). Memoised per
     *  index snapshot: the inspection asks for every changelog it highlights. */
    fun allServiceTables(): List<ServiceTable> {
        serviceTablesCache?.let { return it }
        val tables = ReadAction.computeBlocking<List<ServiceTable>, RuntimeException> {
            index().keysOfType(ModelType.SERVICE).mapNotNull { JsonUtil.readServiceTable(it.file) }
        }
        serviceTablesCache = tables
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
        val collect = { file: VirtualFile ->
            if (!file.isDirectory && !ModelFiles.isExcluded(file.path) &&
                (ModelFiles.typeOf(file) != null || ArchiveModelScanner.isArchive(file))
            ) out.add(file)
        }
        // When an active Flowable sub-project is selected, scan only its subtree (a direct VFS walk,
        // not a ProjectFileIndex prefix-filter, so a folder outside all content roots is still
        // indexed). Otherwise fall back to the whole project's content roots — the historical scope.
        val activeDir = AtlasProjectRootService.getInstance(project).activeProjectDir()
        val base = project.basePath?.let { Path.of(it).normalize() }
        val scopedRoot = if (activeDir != null && base != null && activeDir != base) {
            LocalFileSystem.getInstance().findFileByNioFile(activeDir)
        } else {
            null
        }
        if (scopedRoot != null) {
            VfsUtilCore.iterateChildrenRecursively(
                scopedRoot,
                { vf -> !(vf.isDirectory && vf.name in ModelPaths.EXCLUDE_DIRS) },
                { file -> ProgressManager.checkCanceled(); collect(file); !project.isDisposed },
            )
        } else {
            ProjectFileIndex.getInstance(project).iterateContent { file ->
                ProgressManager.checkCanceled()   // let a long scan be interrupted (e.g. during completion)
                collect(file)
                !project.isDisposed
            }
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
                ArchiveModelScanner.isArchive(file) ->
                    runCatching {
                        ArchiveModelScanner.scan(file) { name, bytes, entryType, entryFile ->
                            processModel(name, bytes, entryType, entryFile)
                        }
                    }.onFailure { LOG.debug("skipping unreadable archive ${file.name}", it) }
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
        )
    }

    /** When a model or archive in scope was last modified, per the cached index; null before a build. */
    fun newestModelMtimeOrNull(): Long? = cachedOrNull()?.newestModelMtime?.takeIf { it > 0 }
}
