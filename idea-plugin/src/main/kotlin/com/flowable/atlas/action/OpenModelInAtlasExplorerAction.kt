package com.flowable.atlas.action

import com.flowable.atlas.AtlasNotifications
import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.intention.OpenInAtlasExplorerIntention
import com.flowable.atlas.model.ModelFiles
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

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
        val entry = index.allEntries().firstOrNull { it.file == file } ?: return
        OpenInAtlasExplorerIntention.openPage(project, entry)
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && !file.isDirectory &&
            ModelFiles.typeOf(file) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
