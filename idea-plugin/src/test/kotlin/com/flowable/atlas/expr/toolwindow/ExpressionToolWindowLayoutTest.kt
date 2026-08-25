package com.flowable.atlas.expr.toolwindow

import com.flowable.atlas.environment.AtlasConnectionSelection
import com.flowable.atlas.environment.AtlasEnvironments
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.expr.inspect.WorkUrlParser
import com.flowable.atlas.expr.inspect.PasteWorkUrlDialog
import com.flowable.atlas.expr.inspect.InspectSession
import com.flowable.atlas.events.AtlasEvents
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

    /**
     * The backend card shows which environment an evaluation runs against, and that choice lives
     * outside the panel — so a switch made anywhere else has to reach an already-open playground. The
     * card used to seed itself once at construction and then outlive every trip to Settings.
     */
    fun testChangingTheSelectedWorkConnectionReachesAnOpenPlayground() {
        val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        FlowableExpressionToolWindowFactory().createToolWindowContent(project, toolWindow)
        val panel = toolWindow.contentManager.contents[0].component as FlowableExpressionPanel
        val catalog = AtlasEnvironments.getInstance()
        val environmentId = catalog.addEnvironment("QA")
        try {
            val id = catalog.addConnection(environmentId, ConnectionKind.WORK, "http://work-qa.example.com")!!
            AtlasConnectionSelection.select(project, ConnectionKind.WORK, id)
            project.messageBus.syncPublisher(AtlasEvents.TOPIC).connectionSelectionChanged(ConnectionKind.WORK)
            UIUtil.dispatchAllInvocationEvents()
            assertEquals("http://work-qa.example.com", panel.inspectBaseUrlText)
        } finally {
            AtlasConnectionSelection.clear(project, ConnectionKind.WORK)
            catalog.removeEnvironment(environmentId)
        }
    }

    /**
     * A Work link is resolved in one dialog now — which app, which instance, and a name plus credentials
     * when the app is not an environment yet. The pieces it leans on are unit-tested on their own
     * ([com.flowable.atlas.environment.WorkConnectionMatcher], `WorkUrlParser`); what matters here is
     * that the card holds no second copy of the connection to drift from the catalog.
     */
    fun testTheCardHoldsNoConnectionOfItsOwn() {
        val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        FlowableExpressionToolWindowFactory().createToolWindowContent(project, toolWindow)
        val panel = toolWindow.contentManager.contents[0].component as FlowableExpressionPanel
        val catalog = AtlasEnvironments.getInstance()
        val environmentId = catalog.addEnvironment("QA")
        try {
            assertEquals("", panel.environmentNameForTest)
            val id = catalog.addConnection(environmentId, ConnectionKind.WORK, "https://work-qa.example.com")!!
            AtlasConnectionSelection.select(project, ConnectionKind.WORK, id)
            project.messageBus.syncPublisher(AtlasEvents.TOPIC).connectionSelectionChanged(ConnectionKind.WORK)
            UIUtil.dispatchAllInvocationEvents()
            assertEquals("QA", panel.environmentNameForTest)
            assertEquals("https://work-qa.example.com", panel.inspectBaseUrlText)
        } finally {
            AtlasConnectionSelection.clear(project, ConnectionKind.WORK)
            catalog.removeEnvironment(environmentId)
        }
    }

    /**
     * The two dialects are different languages against different scopes. Switching the toggle used to
     * carry the text across, which read as "your work is kept" and was the opposite: the expression
     * already parked in the other dialect was replaced, with no way back to it.
     */
    fun testEachDialectKeepsItsOwnExpression() {
        val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        FlowableExpressionToolWindowFactory().createToolWindowContent(project, toolWindow)
        val panel = toolWindow.contentManager.contents[0].component as FlowableExpressionPanel
        val state = FlowableExprPlaygroundState.getInstance(project)
        try {
            panel.switchDialect(ExpressionDialect.BACKEND)
            panel.setExpressionForTest("\${execution.getVariable('amount')}")
            panel.switchDialect(ExpressionDialect.FRONTEND)
            assertFalse(
                "the backend expression must not follow the toggle into the frontend editor",
                panel.expressionForTest.contains("execution"),
            )
            panel.setExpressionForTest("{{ form.amount }}")
            panel.switchDialect(ExpressionDialect.BACKEND)
            assertTrue("switching back brings the backend expression, not the frontend one",
                panel.expressionForTest.contains("execution"))
            panel.switchDialect(ExpressionDialect.FRONTEND)
            assertEquals("{{ form.amount }}", panel.expressionForTest)
        } finally {
            state.setExpression(ExpressionDialect.BACKEND, "")
            state.setExpression(ExpressionDialect.FRONTEND, "")
        }
    }

    /**
     * A pasted link to an app that is not an environment must still be usable — that is the whole point
     * of the one-off route. It is not added to the environment list (nothing is created), so the picker
     * has to show it as the current target rather than falling back to "no environment yet".
     */
    fun testAOneOffUrlBecomesTheTargetAndIsVisibleInThePicker() {
        val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        FlowableExpressionToolWindowFactory().createToolWindowContent(project, toolWindow)
        val panel = toolWindow.contentManager.contents[0].component as FlowableExpressionPanel
        try {
            panel.applyPastedResult(
                PasteWorkUrlDialog.Result(
                    connectionId = null,
                    baseUrl = "http://localhost:9914",
                    username = "demo",
                    password = "secret",
                    parsed = WorkUrlParser.parse("http://localhost:9914/#/work/all/case/CAS-1"),
                ),
            )
            assertEquals("http://localhost:9914", panel.inspectBaseUrlText)
            assertTrue(
                "the picker must name the one-off target, not read as if nothing were selected",
                panel.connectionComboLabelForTest().contains("localhost"),
            )
            assertTrue(panel.connectionComboLabelForTest().contains("this session"))
            assertEquals("", panel.environmentNameForTest)
            assertTrue(
                "credentials for a one-off target stay in memory, never in the password safe",
                InspectSession.get("http://localhost:9914")?.containsKey("Authorization") == true,
            )
            assertTrue(
                "nothing is added to the environment list",
                AtlasEnvironments.getInstance().connections(ConnectionKind.WORK).isEmpty(),
            )
        } finally {
            InspectSession.clear("http://localhost:9914")
        }
    }

    /**
     * The one-off target is never added to the environment list, so the picker is the only place it
     * exists. It has to stay a listed choice while it lives — otherwise glancing at another environment
     * loses it, and the only way back is pasting the link again.
     */
    fun testTheOneOffTargetStaysSelectableAfterLookingAtAnEnvironment() {
        val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        FlowableExpressionToolWindowFactory().createToolWindowContent(project, toolWindow)
        val panel = toolWindow.contentManager.contents[0].component as FlowableExpressionPanel
        val catalog = AtlasEnvironments.getInstance()
        val environmentId = catalog.addEnvironment("QA")
        try {
            val id = catalog.addConnection(environmentId, ConnectionKind.WORK, "https://work-qa.example.com")!!
            panel.applyPastedResult(
                PasteWorkUrlDialog.Result(null, "http://localhost:9914", "", "", WorkUrlParser.parse("")),
            )
            assertEquals("http://localhost:9914", panel.inspectBaseUrlText)

            AtlasConnectionSelection.select(project, ConnectionKind.WORK, id)
            project.messageBus.syncPublisher(AtlasEvents.TOPIC).connectionSelectionChanged(ConnectionKind.WORK)
            UIUtil.dispatchAllInvocationEvents()
            assertEquals("QA", panel.environmentNameForTest)
            assertTrue("the one-off is still offered", panel.connectionItemCountForTest() == 2)

            AtlasConnectionSelection.clear(project, ConnectionKind.WORK)
            project.messageBus.syncPublisher(AtlasEvents.TOPIC).connectionSelectionChanged(ConnectionKind.WORK)
            UIUtil.dispatchAllInvocationEvents()
            assertEquals("and going back to it works", "http://localhost:9914", panel.inspectBaseUrlText)
        } finally {
            AtlasConnectionSelection.clear(project, ConnectionKind.WORK)
            catalog.removeEnvironment(environmentId)
        }
    }
}
