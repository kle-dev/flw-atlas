package com.flowable.atlas.expr.toolwindow

import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.script.toolwindow.FlowableScriptPanel
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.LanguageTextField
import com.intellij.util.ui.UIUtil

/**
 * Layout regression gate for the Flowable Expressions tool window: the Scripts tab must never cost
 * the Expression Playground anything — both tabs exist, and the frontend evaluator (payload editor
 * + live result pane) is still wired inside the Expressions tab.
 */
class ExpressionToolWindowLayoutTest : BasePlatformTestCase() {

    fun testBothTabsExistAndTheFrontendEvaluatorIsIntact() {
        val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        FlowableExpressionToolWindowFactory().createToolWindowContent(project, toolWindow)

        val contents = toolWindow.contentManager.contents
        assertEquals(listOf("Expressions", "Scripts"), contents.map { it.displayName })
        val exprPanel = contents[0].component as FlowableExpressionPanel
        assertTrue(contents[1].component is FlowableScriptPanel)

        // the frontend evaluator: the result pane and at least two editors (expression + payload)
        exprPanel.switchDialect(ExpressionDialect.FRONTEND)
        val resultPanes = UIUtil.findComponentsOfType(exprPanel, PlaygroundResultPane::class.java)
        assertTrue("expected the frontend/backend result panes", resultPanes.size >= 2)
        val editors = UIUtil.findComponentsOfType(exprPanel, LanguageTextField::class.java)
        assertTrue("expected the expression editor and the payload editor", editors.size >= 2)
    }
}
