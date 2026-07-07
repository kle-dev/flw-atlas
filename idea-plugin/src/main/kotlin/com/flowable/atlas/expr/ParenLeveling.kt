package com.flowable.atlas.expr

import com.flowable.atlas.expr.lang.ExpressionLexer
import com.flowable.atlas.expr.lang.TokType

/**
 * Assigns a nesting level to every round parenthesis in an expression body, so a matching `(` / `)`
 * pair share a level and nested pairs step up — the basis for rainbow-colouring parentheses.
 * A matched pair gets the same level: the opener uses the current depth then increments; the closer
 * decrements first, then uses that depth. Pure and unit-testable.
 */
object ParenLeveling {

    data class LevelledParen(val start: Int, val end: Int, val level: Int)

    fun levels(text: String): List<LevelledParen> {
        val out = ArrayList<LevelledParen>()
        var depth = 0
        for (t in ExpressionLexer.tokenize(text)) {
            when (t.type) {
                TokType.LPAREN -> { out += LevelledParen(t.start, t.end, depth); depth++ }
                TokType.RPAREN -> { depth = maxOf(0, depth - 1); out += LevelledParen(t.start, t.end, depth) }
                else -> {}
            }
        }
        return out
    }
}
