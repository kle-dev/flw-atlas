package com.flowable.atlas.expr

import com.flowable.atlas.expr.lang.FlowableExprHighlightingLexer
import com.flowable.atlas.expr.lang.FlowableExprTokenTypes
import com.intellij.psi.tree.IElementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowableExprHighlightingLexerTest {

    private fun tokens(text: String): List<Pair<IElementType?, String>> {
        val lexer = FlowableExprHighlightingLexer()
        lexer.start(text, 0, text.length, 0)
        val out = ArrayList<Pair<IElementType?, String>>()
        while (lexer.tokenType != null) {
            out += lexer.tokenType to text.substring(lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return out
    }

    @Test
    fun coverageIsContiguousAndComplete() {
        val text = "flw.sum( items )"
        val lexer = FlowableExprHighlightingLexer()
        lexer.start(text, 0, text.length, 0)
        var expected = 0
        while (lexer.tokenType != null) {
            assertEquals("no gaps in token coverage", expected, lexer.tokenStart)
            expected = lexer.tokenEnd
            lexer.advance()
        }
        assertEquals("covers to end", text.length, expected)
    }

    @Test
    fun emitsParenAndBracketTokens() {
        val types = tokens("a(b[0])").map { it.first }
        assertTrue(FlowableExprTokenTypes.LPAREN in types)
        assertTrue(FlowableExprTokenTypes.RPAREN in types)
        assertTrue(FlowableExprTokenTypes.LBRACKET in types)
        assertTrue(FlowableExprTokenTypes.RBRACKET in types)
    }

    @Test
    fun whitespaceFillsGaps() {
        // "a b" → IDENT, WHITE_SPACE, IDENT (3 tokens, fully covering the text)
        assertEquals("a b".length, tokens("a b").sumOf { it.second.length })
    }
}
