package com.flowable.atlas.expr.lang

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

/**
 * Matches `()` and `[]` in expressions: places the caret next to a `(` and its matching `)` is
 * highlighted (and an unmatched one is flagged by the editor). Works in the playground field and in
 * injected `${…}` / `{{…}}` fragments. `()` is marked structural so it drives the strongest matching.
 */
class FlowableExprBraceMatcher : PairedBraceMatcher {

    override fun getPairs(): Array<BracePair> = PAIRS

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

    companion object {
        // Parens are level-tagged for rainbow colouring; a matching `(` / `)` share a level, so pair
        // each level with itself. `()` is structural so it drives the strongest matching.
        private val PAIRS = Array(FlowableExprTokenTypes.PAREN_LEVELS) { i ->
            BracePair(FlowableExprTokenTypes.LPAREN_LEVELS[i], FlowableExprTokenTypes.RPAREN_LEVELS[i], true)
        } + BracePair(FlowableExprTokenTypes.LBRACKET, FlowableExprTokenTypes.RBRACKET, false)
    }
}
