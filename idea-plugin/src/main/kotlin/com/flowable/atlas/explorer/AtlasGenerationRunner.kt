package com.flowable.atlas.explorer

import com.intellij.openapi.progress.ProgressManager
import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Shared orchestration around [AtlasGeneratorService]: run generation as a background task, refresh
 * the written files in the VFS, publish [AtlasEvents] and report the outcome. Three callers — the
 * Tools-menu action, the Atlas Hub's Generate button and the explorer editor-toolbar's Regenerate —
 * so the flow lives once here instead of in each action.
 */
object AtlasGenerationRunner {

    /**
     * Generate only the explorer HTML at [outputHtml]. With [quiet] no success balloon is shown
     * (the caller provides its own feedback, e.g. a reloading browser). [onSuccess] runs on the EDT
     * with the freshly refreshed VirtualFile (or null if the VFS could not resolve it).
     */
    fun generateExplorer(
        project: Project,
        outputHtml: Path,
        quiet: Boolean = false,
        onSuccess: ((explorerVf: VirtualFile?) -> Unit)? = null,
    ) {
        // The folder this page was made from, not whichever sub-project is active now.
        val projectDir = AtlasExplorerFiles.rootOf(project, outputHtml) ?: projectDir(project) ?: return
        generateExplorers(project, projectDir, listOf(outputHtml), quiet, onSuccess)
    }

    private fun generateExplorers(
        project: Project,
        projectDir: Path,
        outputs: List<Path>,
        quiet: Boolean = false,
        onSuccess: ((explorerVf: VirtualFile?) -> Unit)? = null,
    ) {
        run(project, projectDir, "Generating Flowable Atlas explorer", quiet, onSuccess) { indicator ->
            AtlasGeneratorService.getInstance(project).generateExplorers(projectDir, outputs, indicator)
        }
    }

    /** Generate the artifacts selected in the project settings into [outputDir]. [onSuccess] runs on
     *  the EDT with the explorer page's VirtualFile — null when the selection did not include it. */
    fun generateAll(
        project: Project,
        outputDir: Path,
        quiet: Boolean = false,
        onSuccess: ((explorerVf: VirtualFile?) -> Unit)? = null,
    ) {
        val projectDir = projectDir(project) ?: return
        run(project, projectDir, "Generating Flowable Atlas artifacts", quiet, onSuccess) { indicator ->
            AtlasGeneratorService.getInstance(project).generateAll(projectDir, outputDir, indicator)
        }
    }

    /**
     * Refresh already-generated artifacts without a dialog — e.g. after a Design pull made them stale.
     * Regenerates the existing explorer page(s) in place when explorer HTML is the only selected
     * artifact; otherwise regenerates the full selected set into the configured output folder.
     */
    fun regenerate(project: Project) {
        val projectDir = projectDir(project) ?: return
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        // The search walks the project six levels deep when the output folder is empty: a visible freeze
        // from a menu click or the Hub's link (OpenAtlasExplorerAction moved the same walk off the EDT).
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Looking for Atlas explorer files", true) {
            private var existing: List<Path> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                existing = AtlasExplorerFiles.find(projectDir, settings.atlasOutputDir)
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                when {
                    // One analysis per report, not per page: pages from the same folder, made from the same
                    // sub-project, share it — each page used to start its own full run, all at once.
                    settings.atlasArtifacts == setOf(AtlasArtifact.EXPLORER_HTML) && existing.isNotEmpty() ->
                        existing.groupBy { (AtlasExplorerFiles.rootOf(project, it) ?: projectDir) to it.parent }
                            .forEach { (key, pages) -> generateExplorers(project, key.first, pages) }
                    // "Regenerate" promises to refresh what exists; with no page on disk the honest answer
                    // is to say so and offer the generator's dialog, not to write the whole artifact set.
                    existing.isEmpty() -> AtlasExplorerNotifier.notifyNoExplorer(project, settings.atlasOutputDir)
                    else -> generateAll(project, projectDir.resolve(settings.atlasOutputDir))
                }
            }
        })
    }

    /** The directory to analyse — the active Flowable sub-project, or the whole project when none. */
    private fun projectDir(project: Project): Path? =
        AtlasProjectRootService.getInstance(project).activeProjectDir()

    private fun run(
        project: Project,
        projectDir: Path,
        title: String,
        quiet: Boolean,
        onSuccess: ((VirtualFile?) -> Unit)?,
        generate: (ProgressIndicator) -> AtlasGeneratorService.Outcome,
    ) {
        // Generation reads the files on disk: an edit still in an editor would be left out.
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) FileDocumentManager.getInstance().saveAllDocuments()
        else app.invokeAndWait { FileDocumentManager.getInstance().saveAllDocuments() }
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val outcome = generate(indicator)
                var explorerVf: VirtualFile? = null
                if (outcome is AtlasGeneratorService.Outcome.Success) {
                    // One refresh for everything written, here on the pooled thread — refreshing file by
                    // file on the EDT froze the IDE for a project with a few hundred diagrams.
                    val lfs = LocalFileSystem.getInstance()
                    lfs.refreshNioFiles(outcome.written)
                    explorerVf = outcome.explorerHtml?.let { lfs.findFileByNioFile(it) }
                    outcome.written.filter { it.fileName.toString().endsWith(".explorer.html") }
                        .forEach { AtlasExplorerFiles.rememberRoot(project, it, projectDir) }
                }
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    when (outcome) {
                        is AtlasGeneratorService.Outcome.Success -> {
                            project.messageBus.syncPublisher(AtlasEvents.TOPIC)
                                .artifactsGenerated(outcome.explorerHtml, outcome.written)
                            AtlasExplorerNotifier.notifySuccess(
                                project, outcome.explorerHtml, explorerVf, outcome.written, quiet,
                            )
                            onSuccess?.invoke(explorerVf)
                        }
                        is AtlasGeneratorService.Outcome.Failure ->
                            AtlasExplorerNotifier.notifyFailure(project, outcome.message, outcome.log)
                    }
                }
            }
        }.queue()
    }
}
