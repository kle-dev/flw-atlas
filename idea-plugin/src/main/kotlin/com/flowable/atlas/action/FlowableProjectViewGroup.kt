package com.flowable.atlas.action

import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelPaths
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware

/**
 * The Project-view context menu entries — *Generate Atlas Explorer…* and *Search Models…* — shown on a
 * folder, a model file or an archive, and on nothing else. The Hub stays the plugin's surface; this is
 * the one place a right-click on the models themselves should not come up empty.
 */
class FlowableProjectViewGroup : DefaultActionGroup(), DumbAware {

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null &&
            (file.isDirectory || ModelFiles.typeOf(file) != null || ModelPaths.isArchive(file.name))
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
