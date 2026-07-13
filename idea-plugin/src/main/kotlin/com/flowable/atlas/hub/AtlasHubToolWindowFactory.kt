package com.flowable.atlas.hub

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * The Atlas Hub tool window (right stripe): the plugin's control center. Shows the model-index
 * status, the generated explorer pages and the Flowable Design sync state, with the shared actions
 * in a toolbar. Registered in plugin.xml; opened via the stripe button or Tools → Flowable Atlas →
 * "Atlas Hub".
 */
class AtlasHubToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AtlasHubPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)   // ties the panel's bus connection + alarm to the tool-window content
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        /** Tool-window ID as registered in plugin.xml — referenced by [com.flowable.atlas.action.OpenAtlasHubAction]. */
        const val ID = "Atlas Hub"
    }
}
