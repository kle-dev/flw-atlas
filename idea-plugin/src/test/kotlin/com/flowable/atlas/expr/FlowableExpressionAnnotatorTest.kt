package com.flowable.atlas.expr

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The annotator reports structural syntax errors only; semantic findings (unknown functions,
 * dialect misuse) moved to [com.flowable.atlas.expr.inspection.FlowableExprUnknownFunctionInspection]
 * — see FlowableExprUnknownFunctionInspectionTest.
 */
class FlowableExpressionAnnotatorTest : BasePlatformTestCase() {

    fun testUnbalancedParenIsError() {
        myFixture.configureByText("t.flowable-be", "date:now(")
        assertTrue(myFixture.doHighlighting().any { it.severity == HighlightSeverity.ERROR })
    }

    fun testValidBackendExpressionHasNoHighlights() {
        myFixture.configureByText("t.flowable-be", "date:now()")
        assertFalse(
            myFixture.doHighlighting().any {
                it.severity == HighlightSeverity.WARNING || it.severity == HighlightSeverity.ERROR
            },
        )
    }

    fun testUnknownFunctionIsNotAnAnnotatorConcern() {
        // Semantic findings must NOT come from the annotator (they'd be unsuppressable there).
        myFixture.configureByText("t.flowable-be", "date:noww()")
        assertFalse(myFixture.doHighlighting().any { it.description?.contains("Unknown function") == true })
    }

    fun testUnterminatedStringIsError() {
        myFixture.configureByText("t.flowable-be", "a == 'oops")
        assertTrue(myFixture.doHighlighting().any { it.severity == HighlightSeverity.ERROR })
    }
}
