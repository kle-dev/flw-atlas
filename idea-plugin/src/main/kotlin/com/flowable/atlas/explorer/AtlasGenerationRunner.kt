package com.flowable.atlas.explorer

import com.flowable.atlas.events.AtlasEvents
import com.intellij.openapi.application.ApplicationManager
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
        val projectDir = projectDir(project) ?: return
        run(project, "Generating Flowable Atlas explorer", quiet, onSuccess) { indicator ->
            AtlasGeneratorService.getInstance(project).generateExplorer(projectDir, outputHtml, indicator)
        }
    }

    /** Generate the artifacts selected in the project settings into [outputDir]. */
    fun generateAll(project: Project, outputDir: Path, quiet: Boolean = false) {
        val projectDir = projectDir(project) ?: return
        run(project, "Generating Flowable Atlas artifacts", quiet, onSuccess = null) { indicator ->
            AtlasGeneratorService.getInstance(project).generateAll(projectDir, outputDir, indicator)
        }
    }

    private fun projectDir(project: Project): Path? = project.basePath?.let { Path.of(it) }

    private fun run(
        project: Project,
        title: String,
        quiet: Boolean,
        onSuccess: ((VirtualFile?) -> Unit)?,
        generate: (ProgressIndicator) -> AtlasGeneratorService.Outcome,
    ) {
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val outcome = generate(indicator)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    when (outcome) {
                        is AtlasGeneratorService.Outcome.Success -> {
                            val lfs = LocalFileSystem.getInstance()
                            outcome.written.forEach { lfs.refreshAndFindFileByNioFile(it) }
                            val explorerVf = outcome.explorerHtml?.let { lfs.refreshAndFindFileByNioFile(it) }
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
