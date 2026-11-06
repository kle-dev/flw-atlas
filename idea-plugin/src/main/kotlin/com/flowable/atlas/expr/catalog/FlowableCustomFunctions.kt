package com.flowable.atlas.expr.catalog

import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.events.AtlasEventsListener
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Project-scoped cache of the project's `externals.additionalData` custom functions (see
 * [CustomFunctionExtractor]), read with the same settings as generation: discovery on or off, and the
 * explicit customization source when one is set — resolved against the same root, the active
 * sub-project ([customFunctionsSource]).
 *
 * The extractor walks the file system — skipping node_modules/dist/target/… and bounded by file count
 * and size — which is too slow for a highlighting pass. [readyOrRequest] therefore never walks: it starts
 * the walk on a pooled thread and answers "not yet", and the daemon is restarted when the catalog lands.
 * A change to a frontend source drops the catalog, so a function added to `externals.additionalData` is
 * known without restarting the IDE; so does an applied settings page or a sub-project switch that
 * changes what the walk would read (the settings are per sub-project).
 */
@Service(Service.Level.PROJECT)
class FlowableCustomFunctions(private val project: Project) : Disposable {

    private val LOG = logger<FlowableCustomFunctions>()

    /** What the walk found — [catalog] null when discovery is off or found nothing readable — and the
     *  [inputs] it was read with. */
    class Ready internal constructor(val catalog: CustomFunctionCatalog?, internal val inputs: Inputs)

    /**
     * Everything a walk depends on: the root it starts from, whether discovery is on, and the explicit
     * source. Kept with the result so a settings event that changed none of them — the event is coarse,
     * fired by every Atlas settings page — does not walk the frontend again.
     */
    internal data class Inputs(val root: File?, val enabled: Boolean, val explicit: File?)

    @Volatile private var ready: Ready? = null
    private val building = AtomicBoolean()
    private val generation = AtomicLong()

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (ready != null && events.any(::touchesFrontendSource)) refresh()
                }
            },
        )
        // The custom-function settings belong to the active sub-project and resolve against it, so both a
        // settings Apply and a sub-project switch can change what the walk reads. Listening to neither, the
        // editor kept the old catalog — flagging or accepting functions the regenerated explorer judged the
        // other way — until a frontend file changed or the IDE restarted.
        connection.subscribe(
            AtlasEvents.TOPIC,
            object : AtlasEventsListener {
                override fun settingsApplied() = onInputsMaybeChanged()
                override fun activeSubProjectChanged() = onInputsMaybeChanged()
            },
        )
    }

    /**
     * The catalog, or null while it is still being built — the caller must then not judge a function
     * unknown, since the project's own functions are simply not read yet. Never walks the file system on
     * the calling thread, except in a unit test, where the build runs inline so results are deterministic.
     */
    fun readyOrRequest(): Ready? {
        ready?.let { return it }
        if (ApplicationManager.getApplication().isUnitTestMode) return build()
        if (building.compareAndSet(false, true)) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    build()
                } finally {
                    building.set(false)
                }
                if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart("Flowable custom functions read")
            }
        }
        return null
    }

    /** The catalog, walking the file system now if need be. Off the EDT only (the playground's worker). */
    fun catalog(): CustomFunctionCatalog? = (ready ?: build()).catalog

    fun refresh() {
        generation.incrementAndGet()
        ready = null
    }

    /**
     * Drops the catalog when what a walk would read now differs from what it was read with. Publishers fire
     * on any thread; [refresh] is a volatile swap and the daemon restart is thread-safe, so nothing hops to
     * the EDT. With no catalog yet, a walk may be running on the old inputs: [refresh] keeps its answer from
     * being stored, and its own daemon restart asks again.
     */
    private fun onInputsMaybeChanged() {
        if (project.isDisposed) return
        val current = ready
        if (current == null) { refresh(); return }
        if (current.inputs == inputs()) return
        refresh()
        DaemonCodeAnalyzer.getInstance(project).restart("Flowable custom-function settings changed")
    }

    /** What a walk started now would read — the same root and source [AtlasGeneratorService] passes. */
    private fun inputs(): Inputs {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        val root = AtlasProjectRootService.getInstance(project).activeProjectDir()?.toFile()
        return Inputs(root, settings.customFunctionsEnabled, root?.let { customFunctionsSource(it, settings.customFunctionsPath) })
    }

    private fun build(): Ready {
        val gen = generation.get()
        val inputs = inputs()
        val base = inputs.root
        val catalog = if (base == null || !inputs.enabled) null else {
            val explicit = inputs.explicit
            // Warn, not debug: an empty catalog does not merely hide a feature, it makes the expression
            // inspection report the project's own custom functions as unknown. A wrong warning in the
            // editor must be traceable to its cause.
            runCatching { CustomFunctionExtractor.extract(explicit ?: base, explicit = explicit) }
                .onFailure { e -> LOG.warn("Extracting custom functions from ${explicit ?: base} failed — project functions will be flagged as unknown", e) }
                .getOrNull()
        }
        val result = Ready(catalog, inputs)
        // A frontend file changed while the walk ran: this answer may already be stale, so it is handed to
        // the caller but not kept.
        if (generation.get() == gen) ready = result
        return result
    }

    private fun touchesFrontendSource(e: VFileEvent): Boolean {
        val name = e.path.substringAfterLast('/').lowercase()
        if (FRONTEND_EXTENSIONS.none { name.endsWith(it) }) return false
        val rel = ModelFiles.projectRelative(project, e.path) ?: return false
        return !ModelFiles.isExcluded(rel)
    }

    /** Seed the catalog directly, bypassing the filesystem walk (the extractor reads real files,
     *  which the in-memory test VFS does not provide). */
    @org.jetbrains.annotations.TestOnly
    fun setForTest(catalog: CustomFunctionCatalog?) {
        ready = Ready(catalog, inputs())
    }

    override fun dispose() {}

    companion object {
        private val FRONTEND_EXTENSIONS = listOf(".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs")

        /**
         * The explicit customization source [path] names, resolved against [root] — the active sub-project,
         * which is also what the settings field's chooser makes the path relative to — or null when [path]
         * is blank or names nothing on disk (then discovery walks [root]). The one resolution the editor's
         * catalog and generation share: the editor resolved against the repository root, missed the file
         * whenever a sub-project was active, and fell back to walking every app in the repository.
         */
        fun customFunctionsSource(root: File, path: String): File? =
            path.takeIf { it.isNotBlank() }?.let { root.resolve(it) }?.takeIf { it.exists() }

        fun getInstance(project: Project): FlowableCustomFunctions =
            project.getService(FlowableCustomFunctions::class.java)
    }
}
