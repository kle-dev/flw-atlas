package com.flowable.atlas.action

import com.flowable.atlas.AtlasNotifications
import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.index.ProjectModelScope
import com.flowable.atlas.intention.OpenInAtlasExplorerIntention
import com.flowable.atlas.model.ModelFiles
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Project view → a model file → *Open in Atlas Explorer*: the model's page in the newest generated
 * explorer, the same step the intention takes from a key under the caret. The Project-view menu used to
 * offer *Go to Model…* here, which ignores what was right-clicked; this is the action about the thing
 * under the cursor.
 */
class OpenModelInAtlasExplorerAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        // The cached index only: a menu click must not wait for a scan. One is requested when there is
        // none, and the balloon says to try again rather than doing nothing.
        val index = project.service<FlowableModelIndexService>().cachedOrRequest()
        if (index == null) {
            AtlasNotifications.info(project, message("explorer.pageNeedsIndex"))
            return
        }
        val entry = index.allEntries().firstOrNull { it.file == file }
        if (entry == null) {
            // The entry stays visible on every model file — greyed out it could not say why — so the click
            // has to: a model in another sub-project, in a build folder, or one without a readable key.
            AtlasNotifications.info(project, whyNotInIndex(project, file))
            return
        }
        OpenInAtlasExplorerIntention.openPage(project, entry)
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && !file.isDirectory &&
            ModelFiles.typeOf(file) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    companion object {
        /**
         * Why the index holds no model for [file], in the order a reader would check: the file is outside the
         * Flowable project chosen in the Hub, it lies in a build or dependency folder the index skips, or no
         * key could be read from it. An entry inside a `.bar`/`.zip` is judged by its archive.
         */
        internal fun whyNotInIndex(project: Project, file: VirtualFile): String {
            val local = if (file.fileSystem is JarFileSystem) JarFileSystem.getInstance().getVirtualFileForJar(file) ?: file else file
            val scopedRoot = ProjectModelScope.scopedRoot(project)
            if (scopedRoot != null && !VfsUtilCore.isAncestor(scopedRoot, local, false)) {
                return message("explorer.page.outsideScope", file.name, ProjectModelScope.label(project) ?: scopedRoot.name)
            }
            if (ModelFiles.excluder(project)(local.path)) return message("explorer.page.excluded", file.name)
            return message("explorer.page.noKey", file.name)
        }
    }
}
