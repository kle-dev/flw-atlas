package com.flowable.atlas.action

import com.flowable.atlas.AtlasNotifications
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Tools → Flowable Atlas → "Open Expression Playground": show and focus the "Expression Playground"
 * tool window. A menu-driven entry point that does not depend on the tool-window stripe button being
 * visible (which requires the plugin to be fully loaded — i.e. the IDE restarted after install).
 */
class OpenExpressionPlaygroundAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (toolWindow == null) {
            AtlasNotifications.info(
                project,
                "The Expression Playground tool window isn't registered yet. If you just installed or " +
                    "updated the plugin, restart the IDE and try again.",
            )
            return
        }
        toolWindow.activate(null, true)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    private companion object {
        const val TOOL_WINDOW_ID = "Expression Playground"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
