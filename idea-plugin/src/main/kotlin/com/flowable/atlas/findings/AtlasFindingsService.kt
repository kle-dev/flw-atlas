package com.flowable.atlas.findings

import com.flowable.atlas.AtlasNotifications
import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.events.AtlasEventsListener
import com.flowable.atlas.explorer.AtlasExplorerStaleness
import com.flowable.atlas.explorer.AtlasGeneratorService
import com.flowable.atlas.explorer.WaiverFileWriter
import com.flowable.atlas.graph.CheckCatalog
import com.flowable.atlas.graph.Waivers
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The findings of the project, from the same in-process analysis the explorer is generated from — for
 * the *Atlas Findings* tool window, which is Swing and so works where the JCEF explorer is slow or absent
 * (Remote Development, an IDE without JCEF).
 *
 * The accepted findings live where the explorer keeps them: `waivers.json` in the analysis output folder,
 * so a rule accepted here shows as accepted on the page and in the CLI's gate, and the other way round.
 *
 * It keeps itself current without running the analysis behind anyone's back — that is a full extract,
 * seconds on a large project. Generating the explorer runs the same analysis, so a generation hands its
 * findings over ([adopt]) and the window follows the page for free. A model changing afterwards does not
 * re-run it either: the analysis is then [stale], and the window and the Hub say so, with *Analyze
 * Again* beside it. Switching the Flowable sub-project drops it, since it described a different folder.
 */
@Service(Service.Level.PROJECT)
class AtlasFindingsService(private val project: Project) : Disposable {

    private val LOG = logger<AtlasFindingsService>()

    /** One analysis: the folder analysed, the folder its `waivers.json` lives in, what it found, and when. */
    class Analysis(
        val root: Path,
        val outputDir: Path,
        val findings: List<Map<String, Any?>>,
        val atMillis: Long = System.currentTimeMillis(),
    ) {
        val defects: Int get() = CheckCatalog.countOpen(findings, CheckCatalog.DEFECT)
        val advice: Int get() = CheckCatalog.countOpen(findings, CheckCatalog.ADVICE)
        val accepted: Int get() = findings.count { it["waived"] != null }
    }

    @Volatile var last: Analysis? = null
        private set

    @Volatile var running = false
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        project.messageBus.connect(this).subscribe(AtlasEvents.TOPIC, object : AtlasEventsListener {
            // A model changed or arrived: the analysis may now be stale — said, not silently re-run.
            override fun modelIndexUpdated() = changed()
            override fun designPullFinished(succeeded: Boolean) = changed()
            override fun activeSubProjectChanged() {
                last = null
                changed()
            }
        })
    }

    /** Whether a model changed since [last] was made — read from the model index the Hub and the explorer
     *  banner read, so the three agree on what "changed" means. */
    val stale: Boolean
        get() {
            val analysis = last ?: return false
            val changedAt = AtlasExplorerStaleness.latestModelChange(project) ?: return false
            return changedAt > analysis.atMillis
        }

    /** The keys of the models that changed since [last] was made; empty when it is current. */
    fun changedSinceAnalysis(): List<String> {
        val analysis = last ?: return emptyList()
        return if (stale) AtlasExplorerStaleness.changedSince(project, analysis.atMillis) else emptyList()
    }

    /**
     * The findings of an analysis someone else ran — the explorer generator, which runs exactly this
     * analysis — so the window follows every generation without a second one. Ignored for a folder other
     * than the active Flowable project, which is what the window is about.
     */
    fun adopt(root: Path, outputDir: Path, findings: List<Map<String, Any?>>) {
        val active = AtlasProjectRootService.getInstance(project).activeProjectDir() ?: return
        if (active.normalize() != root.normalize()) return
        last = Analysis(root, outputDir, findings)
        changed()
    }

    /** Told on the EDT whenever [last] or [running] changes, until [parent] is disposed. */
    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners.add(listener)
        Disposer.register(parent) { listeners.remove(listener) }
    }

    private fun changed() = ApplicationManager.getApplication().invokeLater(
        { if (!project.isDisposed) listeners.forEach { it() } }, project.disposed,
    )

    /** Analyse the active Flowable project in the background. Safe to call from the EDT. */
    fun refresh() {
        if (running) return
        val root = AtlasProjectRootService.getInstance(project).activeProjectDir()
        if (root == null) {
            AtlasNotifications.info(project, message("explorer.noProjectDir"))
            return
        }
        FileDocumentManager.getInstance().saveAllDocuments()
        running = true
        changed()
        object : Task.Backgroundable(project, message("findings.progress"), true) {
            override fun run(indicator: ProgressIndicator) {
                last = analyze(root, indicator)
            }

            override fun onThrowable(error: Throwable) {
                if (error is ProcessCanceledException) return
                LOG.warn("The Atlas findings analysis failed", error)
                AtlasNotifications.group()
                    .createNotification(message("findings.failed"), error.message ?: error.javaClass.simpleName, NotificationType.WARNING)
                    .notify(project)
            }

            override fun onFinished() {
                running = false
                changed()
            }
        }.queue()
    }

    /** The analysis itself, on the calling thread — [refresh] runs it in a background task. */
    internal fun analyze(root: Path, indicator: ProgressIndicator): Analysis {
        val outputDir = root.resolve(FlowableAtlasProjectSettings.getInstance(project).atlasOutputDir)
        val generator = project.service<AtlasGeneratorService>()
        val result = generator.extract(root.toFile(), indicator, generator.waiverFile(outputDir))
        @Suppress("UNCHECKED_CAST")
        val findings = (result["findings"] as? List<Map<String, Any?>>).orEmpty()
        return Analysis(root, outputDir, findings)
    }

    /**
     * Accept [findings] for [reason]: one rule per finding in `waivers.json`, narrowed to its element and
     * subject so it covers that finding and no other, by the git user, dated today, with no expiry. Then
     * analyse again, so the list and the counts follow. Must be called on the EDT.
     */
    fun accept(findings: List<Map<String, Any?>>, reason: String) {
        val analysis = last ?: return
        if (findings.isEmpty()) return
        val by = Waivers.defaultAuthor(analysis.root.toFile())
        val today = LocalDate.now().toString()
        val rules = findings.mapNotNull { f ->
            val check = f["check"] as? String ?: return@mapNotNull null
            val node = f["node"] as? String ?: f["file"] as? String ?: return@mapNotNull null
            Waivers.Waiver(check = check, node = node, element = f["element"] as? String, subject = f["subject"] as? String,
                reason = reason, by = by, at = today)
        }
        try {
            val out = analysis.outputDir.toFile().apply { mkdirs() }
            // The folder is regenerated and may carry client data; waivers.json is the one file in it meant
            // to be kept. The same .gitignore the CLI and the generator write says so.
            Waivers.ensureOutputGitignore(out)
            val dir = VfsUtil.createDirectoryIfMissing(analysis.outputDir.toString()) ?: error("cannot create ${analysis.outputDir}")
            val disk = Waivers.load(out.resolve(Waivers.FILE_NAME))
            val page = Waivers.Set(disk.waivers + rules, disk.notes)
            WaiverFileWriter.write(dir, page, disk.waivers.map { it.sortKey } + disk.notes.map { it.sortKey })
            AtlasNotifications.results()
                .createNotification(message("findings.accepted", rules.size, "${dir.name}/${Waivers.FILE_NAME}"), NotificationType.INFORMATION)
                .notify(project)
        } catch (e: Exception) {
            AtlasNotifications.group()
                .createNotification(message("findings.acceptFailed"), e.message ?: e.toString(), NotificationType.ERROR)
                .notify(project)
            return
        }
        refresh()
    }

    /** For tests: stand in for an analysis that ran, whatever folder it was of. */
    internal fun seedForTest(analysis: Analysis?) {
        last = analysis
        changed()
    }

    override fun dispose() {
        listeners.clear()
    }

    companion object {
        fun getInstance(project: Project): AtlasFindingsService = project.service()
    }
}
