package com.flowable.atlas.action

import com.flowable.atlas.AtlasNotifications
import com.flowable.atlas.FlowableAtlasBundle.message
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

/**
 * *Open Atlas Hub / Findings / Playground*: show and focus one of the plugin's tool windows. A menu entry
 * that does not depend on the stripe button being visible, which it is not until the IDE has restarted
 * after an install. The three used to be three copies of this class that differed in one string each.
 */
abstract class OpenAtlasToolWindowAction(private val toolWindowId: String) : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(toolWindowId)
        if (toolWindow == null) {
            AtlasNotifications.info(project, message("toolwindow.notRegistered", toolWindowId))
            return
        }
        toolWindow.activate(null, true)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
