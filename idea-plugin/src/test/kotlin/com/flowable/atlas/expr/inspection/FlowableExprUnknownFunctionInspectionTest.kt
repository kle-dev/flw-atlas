package com.flowable.atlas.expr.inspection

import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FlowableExprUnknownFunctionInspectionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(FlowableExprUnknownFunctionInspection())
    }

    override fun tearDown() {
        try {
            FlowableAtlasProjectSettings.getInstance(project).loadState(FlowableAtlasProjectSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testUnknownFunctionIsFlaggedWithDidYouMeanFix() {
        myFixture.configureByText("t.flowable-be", "date:no<caret>ww()")
        assertTrue(myFixture.doHighlighting().any { it.description?.contains("Unknown function") == true })

        val fix = myFixture.findSingleIntention("Replace with 'now'")
        myFixture.launchAction(fix)
        assertEquals("date:now()", myFixture.file.text)
    }

    fun testPipeInBackendIsWarned() {
        myFixture.configureByText("t.flowable-be", "a |> b")
        assertTrue(myFixture.doHighlighting().any { it.description?.contains("frontend-only pipe") == true })
    }

    fun testFrontendUnknownMemberIsFlagged() {
        myFixture.configureByText("t.flowable-fe", "flw.sim(items)")
        assertTrue(myFixture.doHighlighting().any { it.description?.contains("flw.sim") == true })
    }

    fun testKnownFunctionIsClean() {
        myFixture.configureByText("t.flowable-be", "date:now()")
        assertFalse(myFixture.doHighlighting().any { it.description?.contains("Unknown") == true })
    }

    fun testFindingInsideInjectedBpmnAttribute() {
        myFixture.configureByText(
            "task.bpmn20.xml",
            """<?xml version="1.0"?>
               <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                            xmlns:flowable="http://flowable.org/bpmn">
                 <process id="P1">
                   <userTask id="t" flowable:assignee="${'$'}{ myns:fn(execution) }"/>
                 </process>
               </definitions>""",
        )
        assertTrue(
            "inspection must reach the injected fragment",
            myFixture.doHighlighting().any { it.description?.contains("Unknown function namespace 'myns'") == true },
        )
    }

    fun testAllowlistedNamespaceIsNotReported() {
        FlowableAtlasProjectSettings.getInstance(project).allow(
            "myns", com.flowable.atlas.expr.ExprProblemKind.UNKNOWN_NAMESPACE,
        )
        myFixture.configureByText("t.flowable-be", "myns:fn()")
        assertFalse(myFixture.doHighlighting().any { it.description?.contains("Unknown") == true })
    }

    fun testAllowlistQuickFixSilencesTheFinding() {
        myFixture.configureByText("t.flowable-be", "my<caret>ns:fn()")
        assertTrue(myFixture.doHighlighting().any { it.description?.contains("Unknown function namespace") == true })

        val fix = myFixture.findSingleIntention("Add namespace 'myns' to Flowable expression allowlist")
        myFixture.launchAction(fix)

        assertTrue("myns" in FlowableAtlasProjectSettings.getInstance(project).state.allowedNamespaces)
        assertFalse(myFixture.doHighlighting().any { it.description?.contains("Unknown") == true })
    }

    fun testAllowlistedNamespaceCoversItsFunctions() {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        settings.allow("date", com.flowable.atlas.expr.ExprProblemKind.UNKNOWN_NAMESPACE)
        myFixture.configureByText("t.flowable-be", "date:noww()")
        assertFalse(myFixture.doHighlighting().any { it.description?.contains("Unknown") == true })
    }
}
