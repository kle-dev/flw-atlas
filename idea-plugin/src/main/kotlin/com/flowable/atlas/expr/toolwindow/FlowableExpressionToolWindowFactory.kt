package com.flowable.atlas.expr.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** Registers the "Flowable Expressions" playground tool window (see plugin.xml). */
class FlowableExpressionToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = FlowableExpressionPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        content.preferredFocusableComponent = panel.focusComponent
        toolWindow.contentManager.addContent(content)
    }
}
