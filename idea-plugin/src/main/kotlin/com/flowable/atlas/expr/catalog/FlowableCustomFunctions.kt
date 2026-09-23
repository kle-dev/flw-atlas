package com.flowable.atlas.expr.catalog

import com.flowable.atlas.model.ModelFiles
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
 * explicit customization source when one is set.
 *
 * The extractor walks the file system — skipping node_modules/dist/target/… and bounded by file count
 * and size — which is too slow for a highlighting pass. [readyOrRequest] therefore never walks: it starts
 * the walk on a pooled thread and answers "not yet", and the daemon is restarted when the catalog lands.
 * A change to a frontend source drops the catalog, so a function added to `externals.additionalData` is
 * known without restarting the IDE.
 */
@Service(Service.Level.PROJECT)
class FlowableCustomFunctions(private val project: Project) : Disposable {

    private val LOG = logger<FlowableCustomFunctions>()

    /** What the walk found — [catalog] null when discovery is off or found nothing readable. */
    class Ready(val catalog: CustomFunctionCatalog?)

    @Volatile private var ready: Ready? = null
    private val building = AtomicBoolean()
    private val generation = AtomicLong()

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (ready != null && events.any(::touchesFrontendSource)) refresh()
                }
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

    private fun build(): Ready {
        val gen = generation.get()
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        val base = project.basePath?.let(::File)
        val catalog = if (base == null || !settings.customFunctionsEnabled) null else {
            val explicit = settings.customFunctionsPath.takeIf { it.isNotBlank() }?.let { base.resolve(it) }?.takeIf { it.exists() }
            // Warn, not debug: an empty catalog does not merely hide a feature, it makes the expression
            // inspection report the project's own custom functions as unknown. A wrong warning in the
            // editor must be traceable to its cause.
            runCatching { CustomFunctionExtractor.extract(explicit ?: base, explicit = explicit) }
                .onFailure { e -> LOG.warn("Extracting custom functions from ${explicit ?: base} failed — project functions will be flagged as unknown", e) }
                .getOrNull()
        }
        val result = Ready(catalog)
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
        ready = Ready(catalog)
    }

    override fun dispose() {}

    companion object {
        private val FRONTEND_EXTENSIONS = listOf(".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs")

        fun getInstance(project: Project): FlowableCustomFunctions =
            project.getService(FlowableCustomFunctions::class.java)
    }
}
