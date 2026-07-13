package com.flowable.atlas.action

import com.flowable.atlas.explorer.AtlasExplorerFiles
import com.flowable.atlas.explorer.AtlasExplorerOpener
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

/**
 * Tools → Flowable Atlas → "Open Atlas Explorer": open an already-generated `*.explorer.html` in the
 * embedded in-IDE viewer, without regenerating it. The complement to "Generate Atlas Explorer" — so
 * once the page exists (from the generator or the standalone `atlas` CLI) it can be reopened straight
 * from the menu instead of hunting for the file in the Project view or switching to a browser.
 *
 * Discovery (see [AtlasExplorerFiles]) looks under the configured output folder first, falling back
 * to a bounded scan of the project. Zero matches offers to generate one; one opens directly; several show a chooser.
 */
class OpenAtlasExplorerAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val base = AtlasProjectRootService.getInstance(project).activeProjectDir()
        if (base == null) {
            Messages.showErrorDialog(project, "This action needs a project directory on disk.", "Flowable Atlas")
            return
        }

        val files = AtlasExplorerFiles.find(base, FlowableAtlasProjectSettings.getInstance(project).atlasOutputDir)
        when (files.size) {
            0 -> offerToGenerate(project, e)
            1 -> openExplorer(project, files.first())
            else -> chooseAndOpen(project, base, files)
        }
    }

    /** Resolve [path] to a (freshly refreshed) VirtualFile and show it in the embedded viewer. */
    private fun openExplorer(project: Project, path: Path) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        if (vf == null) {
            Messages.showErrorDialog(
                project,
                "Could not open $path — the file may have been moved or deleted.",
                "Flowable Atlas",
            )
            return
        }
        AtlasExplorerOpener.openInIde(project, vf)
    }

    private fun chooseAndOpen(project: Project, base: Path, files: List<Path>) {
        // Label each match by its path relative to the project so duplicates across output folders
        // (e.g. atlas-output/<a>/… and atlas-output/<b>/…) are distinguishable. Order is preserved
        // from AtlasExplorerFiles.find (most recently modified first).
        val byLabel = LinkedHashMap<String, Path>()
        for (p in files) {
            val label = runCatching { base.relativize(p).toString() }.getOrDefault(p.fileName.toString())
            byLabel[label] = p
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(byLabel.keys.toList())
            .setTitle("Open Atlas Explorer")
            .setItemChosenCallback { label -> byLabel[label]?.let { openExplorer(project, it) } }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun offerToGenerate(project: Project, e: AnActionEvent) {
        val outputDir = FlowableAtlasProjectSettings.getInstance(project).atlasOutputDir
        val choice = Messages.showYesNoDialog(
            project,
            "No generated Atlas explorer (a *.explorer.html) was found under $outputDir/ or in the " +
                "project.\n\nGenerate one now?",
            "Flowable Atlas",
            "Generate…",
            "Cancel",
            Messages.getQuestionIcon(),
        )
        if (choice == Messages.YES) {
            ActionManager.getInstance().getAction(FlowableActionIds.GENERATE_ATLAS_EXPLORER)
                ?.let { ActionUtil.performAction(it, e) }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }
}
