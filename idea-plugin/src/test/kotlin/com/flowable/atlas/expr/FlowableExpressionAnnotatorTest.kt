package com.flowable.atlas.expr

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FlowableExpressionAnnotatorTest : BasePlatformTestCase() {

    fun testUnknownBackendFunctionIsFlaggedWithDidYouMeanFix() {
        // Caret inside the flagged name so the quick fix is offered at the caret.
        myFixture.configureByText("t.flowable-be", "date:no<caret>ww()")
        val infos = myFixture.doHighlighting()
        assertTrue(infos.any { it.description?.contains("Unknown function") == true })

        val fix = myFixture.findSingleIntention("Replace with 'now'")
        myFixture.launchAction(fix)
        assertEquals("date:now()", myFixture.file.text)
    }

    fun testValidBackendExpressionHasNoHighlights() {
        myFixture.configureByText("t.flowable-be", "date:now()")
        assertFalse(
            myFixture.doHighlighting().any {
                it.severity == HighlightSeverity.WARNING || it.severity == HighlightSeverity.ERROR
            },
        )
    }

    fun testPipeInBackendIsWarned() {
        myFixture.configureByText("t.flowable-be", "a |> b")
        assertTrue(myFixture.doHighlighting().any { it.description?.contains("frontend-only pipe") == true })
    }

    fun testUnbalancedParenIsError() {
        myFixture.configureByText("t.flowable-be", "date:now(")
        assertTrue(myFixture.doHighlighting().any { it.severity == HighlightSeverity.ERROR })
    }

    fun testFrontendUnknownMemberIsFlagged() {
        myFixture.configureByText("t.flowable-fe", "flw.sim(items)")
        assertTrue(myFixture.doHighlighting().any { it.description?.contains("flw.sim") == true })
    }
}
