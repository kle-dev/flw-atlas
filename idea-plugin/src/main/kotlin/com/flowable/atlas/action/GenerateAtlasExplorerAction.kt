package com.flowable.atlas.action

import com.flowable.atlas.explorer.AtlasArtifact
import com.flowable.atlas.explorer.AtlasGenerationRunner
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

/**
 * Tools → Flowable Atlas → "Generate Atlas Explorer…": runs the bundled Atlas generator against the
 * open project. The artifact selection in Settings → Tools → Flowable Atlas → Generation decides
 * what is produced: only the explorer HTML goes to a file you pick; any other selection goes into a
 * folder you pick. The chooser preselects the configured output folder (the setting changes the
 * default location, not the interaction). On success a balloon offers to open the explorer in the
 * external browser or inside the IDE; generated files stay in the project so they can be reopened
 * later.
 */
class GenerateAtlasExplorerAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projectDir = AtlasProjectRootService.getInstance(project).activeProjectDir()
        if (projectDir == null) {
            Messages.showErrorDialog(project, "This action needs a project directory on disk.", "Flowable Atlas")
            return
        }

        if (FlowableAtlasProjectSettings.getInstance(project).atlasArtifacts == setOf(AtlasArtifact.EXPLORER_HTML)) {
            generateExplorerToFile(project, projectDir)
        } else {
            generateAllToFolder(project, projectDir)
        }
    }

    private fun generateExplorerToFile(project: Project, projectDir: Path) {
        val lfs = LocalFileSystem.getInstance()
        val outputDir = FlowableAtlasProjectSettings.getInstance(project).atlasOutputDir
        val baseDir = lfs.findFileByNioFile(projectDir.resolve(outputDir)) ?: lfs.findFileByNioFile(projectDir)
        val defaultName = "${safeName(project)}.explorer.html"

        val descriptor = FileSaverDescriptor(
            "Save Atlas Explorer",
            "Choose where to write the self-contained Atlas HTML page",
            "html",
        )
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(baseDir, defaultName) ?: return   // user cancelled
        AtlasGenerationRunner.generateExplorer(project, wrapper.file.toPath())
    }

    private fun generateAllToFolder(project: Project, projectDir: Path) {
        val lfs = LocalFileSystem.getInstance()
        val outputDir = FlowableAtlasProjectSettings.getInstance(project).atlasOutputDir
        val preselect = lfs.findFileByNioFile(projectDir.resolve(outputDir)) ?: lfs.findFileByNioFile(projectDir)
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Select Output Folder for Atlas Artifacts")
            .withDescription("The artifacts selected in Settings are written here")
        val chosen = FileChooser.chooseFile(descriptor, project, preselect) ?: return   // user cancelled
        AtlasGenerationRunner.generateAll(project, chosen.toNioPath())
    }

    private fun safeName(project: Project): String =
        project.name.ifBlank { "project" }.replace(Regex("""[^A-Za-z0-9._-]"""), "-")

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }
}
