package com.flowable.atlas.action

import com.flowable.atlas.explorer.AtlasArtifactScope
import com.flowable.atlas.explorer.AtlasExplorerNotifier
import com.flowable.atlas.explorer.AtlasGeneratorService
import com.flowable.atlas.settings.FlowableAtlasSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

/**
 * Tools → Flowable Atlas → "Generate Atlas Explorer": runs the bundled Atlas generator against the
 * open project. Depending on the artifact scope in settings it produces either just the
 * self-contained explorer HTML (to a file you pick) or the full set of artifacts (into a folder you
 * pick). On success a balloon offers to open the explorer in the external browser or inside the IDE;
 * generated files stay in the project so they can be reopened later.
 */
class GenerateAtlasExplorerAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath
        if (basePath == null) {
            Messages.showErrorDialog(project, "This action needs a project directory on disk.", "Flowable Atlas")
            return
        }
        val projectDir = Path.of(basePath)

        when (FlowableAtlasSettings.getInstance().atlasArtifactScope) {
            AtlasArtifactScope.EXPLORER_ONLY -> generateExplorerToFile(project, projectDir)
            AtlasArtifactScope.ALL_ARTIFACTS -> generateAllToFolder(project, projectDir)
        }
    }

    private fun generateExplorerToFile(project: Project, projectDir: Path) {
        val lfs = LocalFileSystem.getInstance()
        val baseDir = lfs.findFileByNioFile(projectDir.resolve("atlas-output")) ?: lfs.findFileByNioFile(projectDir)
        val defaultName = "${safeName(project)}.explorer.html"

        val descriptor = FileSaverDescriptor(
            "Save Atlas Explorer",
            "Choose where to write the self-contained Atlas HTML page",
            "html",
        )
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(baseDir, defaultName) ?: return   // user cancelled
        val outputHtml = wrapper.file.toPath()

        runInBackground(project, "Generating Flowable Atlas explorer") { indicator ->
            AtlasGeneratorService.getInstance(project).generateExplorer(projectDir, outputHtml, indicator)
        }
    }

    private fun generateAllToFolder(project: Project, projectDir: Path) {
        val lfs = LocalFileSystem.getInstance()
        val preselect = lfs.findFileByNioFile(projectDir.resolve("atlas-output")) ?: lfs.findFileByNioFile(projectDir)
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Select Output Folder for Atlas Artifacts")
            .withDescription("All artifacts (summary, overview, graph, explorer, CLAUDE.md) are written here")
        val chosen = FileChooser.chooseFile(descriptor, project, preselect) ?: return   // user cancelled
        val outputDir = chosen.toNioPath()

        runInBackground(project, "Generating Flowable Atlas artifacts") { indicator ->
            AtlasGeneratorService.getInstance(project).generateAll(projectDir, outputDir, indicator)
        }
    }

    /** Runs [generate] under a background task, then reports the outcome on the EDT. */
    private fun runInBackground(
        project: Project,
        title: String,
        generate: (ProgressIndicator) -> AtlasGeneratorService.Outcome,
    ) {
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val outcome = generate(indicator)
                ApplicationManager.getApplication().invokeLater {
                    when (outcome) {
                        is AtlasGeneratorService.Outcome.Success -> {
                            val lfs = LocalFileSystem.getInstance()
                            outcome.written.forEach { lfs.refreshAndFindFileByNioFile(it) }
                            val explorerVf = outcome.explorerHtml?.let { lfs.refreshAndFindFileByNioFile(it) }
                            AtlasExplorerNotifier.notifySuccess(project, outcome.explorerHtml, explorerVf, outcome.written)
                        }
                        is AtlasGeneratorService.Outcome.Failure ->
                            AtlasExplorerNotifier.notifyFailure(project, outcome.message, outcome.log)
                    }
                }
            }
        }.queue()
    }

    private fun safeName(project: Project): String =
        project.name.ifBlank { "project" }.replace(Regex("""[^A-Za-z0-9._-]"""), "-")

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }
}
