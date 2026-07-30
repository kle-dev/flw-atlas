package com.flowable.atlas.expr.toolwindow

import com.flowable.atlas.script.toolwindow.FlowableScriptPanel
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** Registers the "Flowable Expressions" playground tool window (see plugin.xml): the Expression
 *  Playground and the Script Playground as two content tabs. */
class FlowableExpressionToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val exprPanel = FlowableExpressionPanel(project)
        val exprContent = ContentFactory.getInstance().createContent(exprPanel, "Expressions", false)
        exprContent.setDisposer(exprPanel)
        exprContent.preferredFocusableComponent = exprPanel.focusComponent
        toolWindow.contentManager.addContent(exprContent)

        val scriptPanel = FlowableScriptPanel(project)
        val scriptContent = ContentFactory.getInstance().createContent(scriptPanel, "Scripts", false)
        scriptContent.setDisposer(scriptPanel)
        scriptContent.preferredFocusableComponent = scriptPanel.focusComponent
        toolWindow.contentManager.addContent(scriptContent)
    }
}
