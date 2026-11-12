package com.flowable.atlas.expr.toolwindow

import com.flowable.atlas.script.toolwindow.FlowableScriptPanel
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** The *Atlas Playground* tool window (see plugin.xml): expressions and scripts, as two content tabs. */
class FlowableExpressionToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // A side dock is tall and narrow: editor over context. The bottom dock is wide: side by side.
        val stacked = !toolWindow.anchor.isHorizontal
        val exprPanel = FlowableExpressionPanel(project, stacked)
        val exprContent = ContentFactory.getInstance().createContent(exprPanel, "Expressions", false)
        exprContent.setDisposer(exprPanel)
        exprContent.preferredFocusableComponent = exprPanel.focusComponent
        toolWindow.contentManager.addContent(exprContent)

        val scriptPanel = FlowableScriptPanel(project, stacked)
        val scriptContent = ContentFactory.getInstance().createContent(scriptPanel, "Scripts", false)
        scriptContent.setDisposer(scriptPanel)
        scriptContent.preferredFocusableComponent = scriptPanel.focusComponent
        toolWindow.contentManager.addContent(scriptContent)
    }

    companion object {
        /** Tool-window ID as registered in plugin.xml. It names both tabs, which is why it is not
         *  "Expression Playground" any more: half of what it holds is scripts. */
        const val ID = "Atlas Playground"
    }
}
