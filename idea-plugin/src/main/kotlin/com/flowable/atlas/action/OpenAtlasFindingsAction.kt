package com.flowable.atlas.action

import com.flowable.atlas.AtlasNotifications
import com.flowable.atlas.findings.AtlasFindingsToolWindowFactory
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

/** Tools → Flowable Atlas → "Atlas Findings": show and focus the findings tool window. */
class OpenAtlasFindingsAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(AtlasFindingsToolWindowFactory.ID)
        if (toolWindow == null) {
            AtlasNotifications.info(
                project,
                "The Atlas Findings tool window isn't registered yet. If you just installed or updated " +
                    "the plugin, restart the IDE and try again.",
            )
            return
        }
        toolWindow.activate(null, true)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
