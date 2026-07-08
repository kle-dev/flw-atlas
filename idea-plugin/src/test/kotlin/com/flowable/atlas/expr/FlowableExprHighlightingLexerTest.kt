package com.flowable.atlas.expr

import com.flowable.atlas.expr.lang.FlowableExprHighlightingLexer
import com.flowable.atlas.expr.lang.FlowableExprTokenTypes
import com.intellij.psi.tree.IElementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowableExprHighlightingLexerTest {

    private fun lexer(text: String, state: Int = 0): FlowableExprHighlightingLexer {
        val lx = FlowableExprHighlightingLexer()
        lx.start(text, 0, text.length, state)
        return lx
    }

    private fun tokens(text: String): List<Pair<IElementType?, String>> {
        val lx = lexer(text)
        val out = ArrayList<Pair<IElementType?, String>>()
        while (lx.tokenType != null) {
            out += lx.tokenType to text.substring(lx.tokenStart, lx.tokenEnd)
            lx.advance()
        }
        return out
    }

    /** Type of the token that starts exactly at [offset], or null. */
    private fun typeAt(text: String, offset: Int, state: Int = 0): IElementType? {
        val lx = lexer(text, state)
        while (lx.tokenType != null) {
            if (lx.tokenStart == offset) return lx.tokenType
            lx.advance()
        }
        return null
    }

    @Test
    fun coverageIsContiguousAndComplete() {
        val text = "flw.sum( items )"
        val lx = lexer(text)
        var expected = 0
        while (lx.tokenType != null) {
            assertEquals("no gaps in token coverage", expected, lx.tokenStart)
            expected = lx.tokenEnd
            lx.advance()
        }
        assertEquals("covers to end", text.length, expected)
    }

    @Test
    fun emitsParenAndBracketTokens() {
        val types = tokens("a(b[0])").map { it.first }
        assertTrue(FlowableExprTokenTypes.LPAREN in types)       // level-0 alias
        assertTrue(FlowableExprTokenTypes.RPAREN in types)
        assertTrue(FlowableExprTokenTypes.LBRACKET in types)
        assertTrue(FlowableExprTokenTypes.RBRACKET in types)
    }

    @Test
    fun whitespaceFillsGaps() {
        assertEquals("a b".length, tokens("a b").sumOf { it.second.length })
    }

    @Test
    fun matchingPairSharesColourAndNestingCyclesInward() {
        // "((x))" : ( ( x ) ) at offsets 0 1 2 3 4 — nested, so colours cycle 0,1 inward.
        val t = "((x))"
        assertEquals(FlowableExprTokenTypes.LPAREN_LEVELS[0], typeAt(t, 0))   // outer (
        assertEquals(FlowableExprTokenTypes.LPAREN_LEVELS[1], typeAt(t, 1))   // inner (
        assertEquals(FlowableExprTokenTypes.RPAREN_LEVELS[1], typeAt(t, 3))   // inner ) — matches inner (
        assertEquals(FlowableExprTokenTypes.RPAREN_LEVELS[0], typeAt(t, 4))   // outer ) — matches outer (
    }

    @Test
    fun siblingPairsGetDistinctColours() {
        // "(a)+(b)+(c)" : three non-nested pairs must cycle 0,1,2 (not all the same).
        val t = "(a)+(b)+(c)"
        assertEquals(FlowableExprTokenTypes.LPAREN_LEVELS[0], typeAt(t, 0))
        assertEquals(FlowableExprTokenTypes.RPAREN_LEVELS[0], typeAt(t, 2))
        assertEquals(FlowableExprTokenTypes.LPAREN_LEVELS[1], typeAt(t, 4))
        assertEquals(FlowableExprTokenTypes.RPAREN_LEVELS[1], typeAt(t, 6))
        assertEquals(FlowableExprTokenTypes.LPAREN_LEVELS[2], typeAt(t, 8))
        assertEquals(FlowableExprTokenTypes.RPAREN_LEVELS[2], typeAt(t, 10))
    }

    @Test
    fun openerColourCyclesModuloPaletteSize() {
        // Six sibling openers → colours 0,1,2,3,4,0 (PAREN_LEVELS == 5).
        val n = FlowableExprTokenTypes.PAREN_LEVELS
        val t = "()".repeat(6)
        for (i in 0 until 6)
            assertEquals("pair $i opener", FlowableExprTokenTypes.LPAREN_LEVELS[i % n], typeAt(t, i * 2))
    }

    @Test
    fun colourCursorAndStackSurviveIncrementalRelex() {
        // Fully lex "(a)+(b)"; capture the state just before the 2nd '(' (offset 4). Restarting the
        // lexer at that offset with the carried state must continue the cycle → 2nd pair is colour 1.
        val t = "(a)+(b)"
        val lx = lexer(t)
        var stateBefore2nd = -1
        while (lx.tokenType != null) {
            if (lx.tokenStart == 4) stateBefore2nd = lx.state
            lx.advance()
        }
        assertEquals(FlowableExprTokenTypes.LPAREN_LEVELS[1], typeAt(t.substring(4), 0, state = stateBefore2nd))

        // And a closer whose opener is before the restart point still matches its opener's colour:
        // lex "((x))", restart at the first ')' (offset 3) with the state carried there.
        val u = "((x))"
        val lx2 = lexer(u)
        var stateBeforeClose = -1
        while (lx2.tokenType != null) {
            if (lx2.tokenStart == 3) stateBeforeClose = lx2.state
            lx2.advance()
        }
        assertEquals(FlowableExprTokenTypes.RPAREN_LEVELS[1], typeAt(u.substring(3), 0, state = stateBeforeClose))
    }
}
