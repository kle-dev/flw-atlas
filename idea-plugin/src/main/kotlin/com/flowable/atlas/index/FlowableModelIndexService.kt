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
import com.flowable.atlas.parsing.ParamInfo
import com.flowable.atlas.parsing.ServiceTable
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
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

/**
 * Project-wide index of Flowable model keys and the query facade used by the completion
 * contributors. Backed by a cached full scan of the project's content roots; invalidated
 * when any model file changes. The public API (keysOfType / find / operationsOf /
 * inputParametersOf) is intentionally storage-agnostic so the backing store can later be
 * swapped for a FileBasedIndex without touching callers.
 */
private val LOG = logger<FlowableModelIndexService>()

@Service(Service.Level.PROJECT)
class FlowableModelIndexService(private val project: Project) : Disposable {

    @Volatile
    private var cached: FlowableIndex? = null

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    if (events.any { ModelFiles.isModelPath(it.path) }) {
                        cached = null
                        publishUpdated()
                    }
                }
            },
        )
    }

    /** The current index, building it (under a read action) on first use / after invalidation. */
    fun index(): FlowableIndex {
        cached?.let { return it }
        val built = ReadAction.computeBlocking<FlowableIndex, RuntimeException> { build() }
        cached = built
        publishUpdated()
        return built
    }

    /**
     * The cached index if one exists, without triggering a (blocking) build. The Atlas Hub's
     * status display uses this — the full scan must never run on the EDT.
     */
    fun cachedOrNull(): FlowableIndex? = cached

    /** Force a rebuild and return the fresh index. */
    fun refresh(): FlowableIndex {
        cached = null
        return index()
    }

    /** Drop the cached index so it is rebuilt lazily on next use (cheap; safe on the EDT). */
    fun invalidate() {
        cached = null
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

    /** Operations available on a data object, resolved via its backing service model. */
    fun operationsOf(dataObjectKey: String): List<OperationInfo> {
        val dataFile = index().find(dataObjectKey, ModelType.DATA_OBJECT)?.file ?: return emptyList()
        val serviceKey = ReadAction.computeBlocking<String?, RuntimeException> {
            JsonUtil.topLevelString(dataFile, "referencedServiceDefinitionModelKey")
        } ?: return emptyList()
        return operationsOfService(serviceKey)
    }

    /** Operations declared directly on a service model. */
    fun operationsOfService(serviceKey: String): List<OperationInfo> {
        val serviceFile = index().find(serviceKey, ModelType.SERVICE)?.file ?: return emptyList()
        return ReadAction.computeBlocking<List<OperationInfo>, RuntimeException> {
            JsonUtil.readOperations(serviceFile)
        }
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

    /** All indexed database `.service` models (for the Liquibase-coverage inspection). */
    fun allServiceTables(): List<ServiceTable> = ReadAction.computeBlocking<List<ServiceTable>, RuntimeException> {
        index().keysOfType(ModelType.SERVICE).mapNotNull { JsonUtil.readServiceTable(it.file) }
    }

    /** All indexed `.data` models. */
    fun allDataObjects(): List<DataObjectInfo> = ReadAction.computeBlocking<List<DataObjectInfo>, RuntimeException> {
        index().keysOfType(ModelType.DATA_OBJECT).mapNotNull { JsonUtil.readDataObject(it.file) }
    }

    override fun dispose() {
        cached = null
    }

    // ---- scanning ----------------------------------------------------------------------

    private fun build(): FlowableIndex {
        val byKey = HashMap<String, MutableList<ModelEntry>>()
        val referencedIdentifiers = HashSet<String>()
        val referencedClassFqns = HashSet<String>()
        val variables = HashSet<String>()
        val messages = HashSet<String>()
        val signals = HashSet<String>()
        val userTaskIds = HashSet<String>()
        val activityIds = HashSet<String>()
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
                ModelRefScanner.scan(String(bytes, Charsets.UTF_8), referencedIdentifiers, referencedClassFqns)
            } catch (e: Exception) {
                // unreadable / not valid — skip this model, but leave a trace: a systematically
                // mis-parsed model type would otherwise silently never be indexed
                LOG.debug("skipping unindexable model $fileName", e)
            }
        }

        fun process(file: VirtualFile) {
            if (!file.isDirectory && !ModelFiles.isExcluded(file.path)) {
                val type = ModelFiles.typeOf(file)
                when {
                    type != null ->
                        runCatching { file.contentsToByteArray() }.getOrNull()
                            ?.let { processModel(file.name, it, type, file) }
                    // Look inside .bar/.zip archives (real-world deployment; unpacked folder optional).
                    ArchiveModelScanner.isArchive(file) ->
                        ArchiveModelScanner.scan(file) { name, bytes, entryType, entryFile ->
                            processModel(name, bytes, entryType, entryFile)
                        }
                }
            }
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
                { file -> ProgressManager.checkCanceled(); process(file); true },
            )
        } else {
            ProjectFileIndex.getInstance(project).iterateContent { file ->
                ProgressManager.checkCanceled()   // let a long scan be interrupted (e.g. during completion)
                process(file)
                true
            }
        }
        return FlowableIndex(
            byKey, referencedIdentifiers, referencedClassFqns,
            variables = variables, messages = messages, signals = signals,
            userTaskIds = userTaskIds, activityIds = activityIds,
            builtAtMillis = System.currentTimeMillis(),
        )
    }
}
