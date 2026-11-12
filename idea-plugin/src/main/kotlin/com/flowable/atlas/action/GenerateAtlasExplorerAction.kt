package com.flowable.atlas.action

import com.flowable.atlas.AtlasNotifications
import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.explorer.AtlasArtifact
import com.flowable.atlas.explorer.AtlasExplorerOpener
import com.flowable.atlas.explorer.AtlasGenerationRunner
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Tools → Flowable Atlas → "Generate Atlas Explorer…": runs the bundled Atlas generator against the
 * open project. The artifact selection in Settings → Tools → Flowable Atlas → Generation decides
 * what is produced: only the explorer HTML goes to a file you pick; any other selection goes into a
 * folder you pick. The chooser preselects the configured output folder (the setting changes the
 * default location, not the interaction). On success a balloon offers to open the explorer in the
 * external browser or inside the IDE; generated files stay in the project so they can be reopened
 * later.
 *
 * From the Project view it is about the folder under the cursor: on a folder inside another detected
 * Flowable sub-project than the active one, the entry names that sub-project and makes it the active one
 * before generating — the switch the Hub's picker makes, so that project's settings apply.
 */
class GenerateAtlasExplorerAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Right-clicked on another sub-project's folder: generating the active one would ignore the click,
        // and analysing that folder under the active one's settings would mix the two.
        targetSubProject(project, e.place, e.getData(CommonDataKeys.VIRTUAL_FILE))
            ?.let { AtlasProjectRootService.getInstance(project).setActiveSubProject(it) }
        val projectDir = AtlasProjectRootService.getInstance(project).activeProjectDir()
        if (projectDir == null) {
            AtlasNotifications.info(project, message("explorer.noProjectDir"))
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
        AtlasGenerationRunner.generateExplorer(project, wrapper.file.toPath(), onSuccess = openWhenDone(project))
    }

    /** The page you asked for opens when it is ready — the balloon's "Open in IDE" used to be the
     *  only way there, and it expired with the balloon. */
    private fun openWhenDone(project: Project): (VirtualFile?) -> Unit = { vf ->
        if (vf != null && !project.isDisposed) AtlasExplorerOpener.openInIde(project, vf)
    }

    private fun generateAllToFolder(project: Project, projectDir: Path) {
        val lfs = LocalFileSystem.getInstance()
        val outputDir = FlowableAtlasProjectSettings.getInstance(project).atlasOutputDir
        val preselect = lfs.findFileByNioFile(projectDir.resolve(outputDir)) ?: lfs.findFileByNioFile(projectDir)
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Select Output Folder for Atlas Artifacts")
            .withDescription("The artifacts selected in Settings are written here")
        val chosen = FileChooser.chooseFile(descriptor, project, preselect) ?: return   // user cancelled
        AtlasGenerationRunner.generateAll(project, chosen.toNioPath(), onSuccess = openWhenDone(project))
    }

    /**
     * What to call the page: the **directory Atlas analysed**, not the IntelliJ project.
     *
     * Those are the same thing in a single-project repository, and they are not when the IDE is opened
     * on a folder that *contains* the Flowable project — the ordinary monorepo case, and the one where
     * the name matters. The page then arrived called after the parent folder, which says nothing about
     * what is in it, while the Atlas Hub was analysing (and searching) the sub-project all along.
     */
    internal fun safeName(project: Project): String {
        val base = project.basePath?.let { Path.of(it).normalize() }
        // Only when the scope is *narrower* than the project. Equal to the base means the whole
        // repository is being analysed, and then `project.name` is the better answer: it is what the
        // title bar says and it honours a project someone deliberately renamed. It also covers a stale
        // sub-project — activeProjectDir() widens back to the base — so the page is never named after
        // a folder that is gone.
        val analysed = AtlasProjectRootService.getInstance(project).activeProjectDir()
            ?.takeIf { it != base }
            ?.fileName?.toString()
        return (analysed ?: project.name).ifBlank { "project" }
            .replace(Regex("""[^A-Za-z0-9._-]"""), "-")
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project?.basePath != null
        if (project == null) return
        // The menu says it before the click does it.
        targetSubProject(project, e.place, e.getData(CommonDataKeys.VIRTUAL_FILE))
            ?.let { e.presentation.text = message("explorer.generateFor", it) }
    }

    /**
     * The sub-project a Project-view click on [file] is about, when that is not the active one — null
     * anywhere else (the menu, the Hub), on a folder outside every detected sub-project, and before
     * detection has run: [AtlasProjectRootService.detectedOrNull] is a field read, never a walk, because
     * this runs on every update.
     */
    internal fun targetSubProject(project: Project, place: String, file: VirtualFile?): String? {
        if (place != ActionPlaces.PROJECT_VIEW_POPUP || file == null) return null
        val selected = file.fileSystem.getNioPath(file) ?: return null
        val base = project.basePath?.let { Path.of(it) } ?: return null
        val roots = AtlasProjectRootService.getInstance(project)
        val detected = roots.detectedOrNull()?.takeIf { it.size >= 2 } ?: return null
        return AtlasProjectRootService.owningSubProject(base, detected, selected)?.takeIf { it != roots.activeSubProject() }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
