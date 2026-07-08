package com.flowable.atlas.expr

import com.flowable.atlas.expr.annotator.FlowableExprRainbowParenAnnotator
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Rainbow parentheses must be *coloured* — not merely annotated. The colours are forced onto the
 * annotation ([FlowableExprRainbowParenAnnotator] uses `enforcedTextAttributes`), so each paren's
 * [HighlightInfo.forcedTextAttributes] carries the level colour. Regression guard for "the colours
 * are gone again": a plain INFORMATION annotation with no forced attributes would let the parens
 * fall back to the highlighter's single flat paren colour.
 */
class FlowableExprRainbowParenAnnotatorTest : BasePlatformTestCase() {

    private val palette = FlowableExprRainbowParenAnnotator.LEVEL_ATTRS

    /** Forced attributes of the paren character at [offset] in the current file, or null. */
    private fun parenColorAt(offset: Int): TextAttributes? =
        myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.INFORMATION && it.forcedTextAttributes != null }
            .firstOrNull { it.startOffset == offset && it.endOffset == offset + 1 }
            ?.forcedTextAttributes

    fun testEachParenIsForcedToItsLevelColour() {
        // "(1+1) || (2+2+(1))" — paren offsets and their nesting levels:
        //   ( 1 + 1 )  _ | | _ ( 2  +  2  +  (  1  )  )
        //   0       4        9 10       13 14 15 16 17
        myFixture.configureByText("t.flowable-fe", "(1+1) || (2+2+(1))")
        assertEquals("outer pair level 0", palette[0], parenColorAt(0))   // (
        assertEquals("outer pair level 0", palette[0], parenColorAt(4))   // )
        assertEquals("second group level 0", palette[0], parenColorAt(9)) // (
        assertEquals("nested ( level 1", palette[1], parenColorAt(14))    // (
        assertEquals("nested ) level 1", palette[1], parenColorAt(16))    // )
        assertEquals("second group close level 0", palette[0], parenColorAt(17)) // )
    }

    fun testDeepNestingCyclesThroughDistinctColours() {
        // "((((()))))" would exceed the palette; "((((x))))" reaches level 3 then mirrors back.
        myFixture.configureByText("t.flowable-fe", "((((x))))")
        assertEquals(palette[0], parenColorAt(0))
        assertEquals(palette[1], parenColorAt(1))
        assertEquals(palette[2], parenColorAt(2))
        assertEquals(palette[3], parenColorAt(3))
        // matching closers mirror the openers' levels
        assertEquals(palette[3], parenColorAt(5))
        assertEquals(palette[0], parenColorAt(8))
    }

    fun testRainbowRendersInsideInjectedFrontendBinding() {
        // The real target: a {{…}} binding inside a .form JSON model. The injected fragment must
        // still get the forced rainbow colours (they transfer to the host editor).
        myFixture.configureByText(
            "sample.form",
            """{ "key": "f", "fields": [ { "value": "{{ (1+1) || (2+(3)) }}" } ] }""",
        )
        val text = myFixture.file.text
        val open = text.indexOf("(1+1)")            // level-0 opener
        val nestedOpen = text.indexOf("(3)")        // level-1 opener
        assertEquals(palette[0], parenColorAt(open))
        assertEquals(palette[1], parenColorAt(nestedOpen))
    }
}
