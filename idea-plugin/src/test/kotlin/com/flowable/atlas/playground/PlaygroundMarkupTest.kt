package com.flowable.atlas.playground

import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** A playground finding is painted with the editor scheme's own error and warning attributes. */
class PlaygroundMarkupTest : BasePlatformTestCase() {

    fun testProblemsWearTheSchemesAttributesAndAZeroLengthOneGetsAShape() {
        myFixture.configureByText("scratch.txt", "abc def ghi")
        val editor = myFixture.editor as EditorEx
        val markup = PlaygroundMarkup()
        markup.attach(editor)

        markup.paint(
            listOf(
                PlaygroundProblem(0, 3, "an error", isError = true),
                PlaygroundProblem(4, 4, "a warning with no width", isError = false),
            ),
        )
        val ours = editor.markupModel.allHighlighters.filter { it.errorStripeTooltip != null }
        assertEquals(2, ours.size)
        val error = ours.single { it.errorStripeTooltip == "an error" }
        val warning = ours.single { it.errorStripeTooltip == "a warning with no width" }
        // The scheme's keys, not hand-picked colours: a user who tuned "Errors and Warnings" sees it here.
        assertEquals(CodeInsightColors.ERRORS_ATTRIBUTES, error.textAttributesKey)
        assertEquals(CodeInsightColors.WARNINGS_ATTRIBUTES, warning.textAttributesKey)
        assertEquals(2, warning.endOffset - warning.startOffset)

        markup.paint(emptyList())
        assertTrue(editor.markupModel.allHighlighters.none { it.errorStripeTooltip != null })
    }
}
