package com.flowable.atlas.action

import com.flowable.atlas.AtlasNotifications
import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.explorer.AtlasArtifact
import com.flowable.atlas.explorer.AtlasBrowser
import com.flowable.atlas.explorer.AtlasExplorerFiles
import com.flowable.atlas.explorer.AtlasExplorerOpener
import com.flowable.atlas.explorer.AtlasGenerationRunner
import com.flowable.atlas.explorer.JcefSupport
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Tools → Flowable Atlas → "Open Atlas ER Diagram Designer" (so also the Atlas Hub's ⋮, which is that menu): the
 * newest `*.erd.html` in an editor tab — and, when there is none yet, the page generated into the output
 * folder first, so the entry always ends on the designer rather than on a balloon about a missing file. A menu
 * entry and no button: the designer is an occasional tool, and the Hub's Explorer block stays about the explorer.
 *
 * Offered only while the project has chosen the page (Settings → Generation → *ER diagram designer
 * (HTML)*): the designer is an artifact a team opts into, and an entry for something it never asked for
 * is noise in a menu that is already long.
 */
class OpenAtlasErDesignerAction : AnAction(), DumbAware {

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && isChosen(project)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val base = AtlasProjectRootService.getInstance(project).activeProjectDir()
        if (base == null) {
            AtlasNotifications.info(project, message("explorer.noProjectDir"))
            return
        }
        val outputDir = FlowableAtlasProjectSettings.getInstance(project).atlasOutputDir
        // The same walk as Open Atlas Explorer's — the output folder first, then a bounded scan — off the EDT.
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Looking for the ER diagram page", true) {
            private var pages: List<Path> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                pages = AtlasExplorerFiles.find(base, outputDir, AtlasArtifact.ERD_HTML.suffix)
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                val newest = pages.firstOrNull()
                if (newest != null) open(project, newest) else AtlasGenerationRunner.generateErdPage(project) { vf -> vf?.let { open(project, it) } }
            }
        })
    }

    private fun open(project: Project, path: Path) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        if (vf != null) open(project, vf) else if (AtlasBrowser.canOpenFiles()) AtlasBrowser.open(path)
    }

    private fun open(project: Project, vf: VirtualFile) {
        if (JcefSupport.isAvailable()) AtlasExplorerOpener.openInIde(project, vf)
        else if (AtlasBrowser.canOpenFiles()) AtlasBrowser.open(vf.toNioPath())
    }

    companion object {
        /** Whether the project generates the ER diagram page — the condition for every entry that opens it. */
        fun isChosen(project: Project): Boolean =
            AtlasArtifact.ERD_HTML in FlowableAtlasProjectSettings.getInstance(project).atlasArtifacts
    }
}
