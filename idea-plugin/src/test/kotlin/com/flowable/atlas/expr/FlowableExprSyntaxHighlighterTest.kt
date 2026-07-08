package com.flowable.atlas.expr

import com.flowable.atlas.expr.lang.FlowableExprSyntaxHighlighter
import com.flowable.atlas.expr.lang.FlowableExprTokenTypes
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color

/**
 * The rainbow colours are produced by the syntax highlighter (so they paint in the playground's
 * embedded editor field, not only in a full editor). This verifies each paren nesting level maps to
 * a distinct key that resolves to a concrete foreground colour through the active colour scheme —
 * exactly the resolution the editor highlighter performs when painting.
 */
class FlowableExprSyntaxHighlighterTest : BasePlatformTestCase() {

    private val hl = FlowableExprSyntaxHighlighter()

    private fun foreground(key: TextAttributesKey): Color? {
        val scheme = EditorColorsManager.getInstance().globalScheme
        return scheme.getAttributes(key)?.foregroundColor ?: key.defaultAttributes?.foregroundColor
    }

    fun testEachParenLevelHasADistinctResolvedColour() {
        val colours = mutableListOf<Color>()
        for (i in 0 until FlowableExprTokenTypes.PAREN_LEVELS) {
            val keys = hl.getTokenHighlights(FlowableExprTokenTypes.LPAREN_LEVELS[i])
            assertEquals("one key per opener level $i", 1, keys.size)
            // opener and its matching closer level use the same key (matching pair, same colour)
            assertEquals(keys[0], hl.getTokenHighlights(FlowableExprTokenTypes.RPAREN_LEVELS[i])[0])
            val fg = foreground(keys[0])
            assertNotNull("level $i resolves to a foreground colour", fg)
            colours += fg!!
        }
        assertEquals("all five levels are visually distinct", colours.size, colours.toSet().size)
    }
}
