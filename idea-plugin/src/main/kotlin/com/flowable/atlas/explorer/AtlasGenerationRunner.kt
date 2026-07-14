package com.flowable.atlas.explorer

import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
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

    /**
     * Refresh already-generated artifacts without a dialog — e.g. after a Design pull made them stale.
     * Regenerates the existing explorer page(s) in place when explorer HTML is the only selected
     * artifact; otherwise regenerates the full selected set into the configured output folder.
     */
    fun regenerate(project: Project) {
        val projectDir = projectDir(project) ?: return
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        val existing = AtlasExplorerFiles.find(projectDir, settings.atlasOutputDir)
        if (settings.atlasArtifacts == setOf(AtlasArtifact.EXPLORER_HTML) && existing.isNotEmpty()) {
            existing.forEach { generateExplorer(project, it) }
        } else {
            generateAll(project, projectDir.resolve(settings.atlasOutputDir))
        }
    }

    /** The directory to analyse — the active Flowable sub-project, or the whole project when none. */
    private fun projectDir(project: Project): Path? =
        AtlasProjectRootService.getInstance(project).activeProjectDir()

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
