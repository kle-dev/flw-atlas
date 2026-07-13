package com.flowable.atlas.action

import com.flowable.atlas.hub.AtlasHubToolWindowFactory
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Tools → Flowable Atlas → "Atlas Hub": show and focus the Atlas Hub tool window — the plugin's
 * control center (model-index status, generated explorers, Flowable Design sync). A menu-driven
 * entry point that does not depend on the tool-window stripe button being visible (which requires
 * the plugin to be fully loaded — i.e. the IDE restarted after install).
 */
class OpenAtlasHubAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(AtlasHubToolWindowFactory.ID)
        if (toolWindow == null) {
            Messages.showInfoMessage(
                project,
                "The Atlas Hub tool window isn't registered yet. If you just installed or updated " +
                    "the plugin, restart the IDE and try again.",
                "Flowable Atlas",
            )
            return
        }
        toolWindow.activate(null, true)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
