package com.flowable.atlas.expr.lang

import com.intellij.psi.tree.IElementType

/**
 * Token element types used by the editor highlighter + brace matcher (NOT by the single-leaf PSI
 * parser). Shared by both dialects — coloring and brace matching key off the token identity, so the
 * types are declared once (nominally under the backend language).
 *
 * Round parens carry a **colour level** in the token type ([LPAREN_LEVELS] / [RPAREN_LEVELS]), so the
 * syntax highlighter can rainbow-colour them purely through the lexer — this renders in every surface,
 * including the playground's embedded editor field where annotator-based highlighting does not
 * reliably paint. The level is assigned per pair by [FlowableExprHighlightingLexer] (each opening `(`
 * takes the next colour; its matching `)` reuses it), so a pair's `(` / `)` always share a level and
 * the paired brace matcher can pair `LPAREN_LEVELS[i]` with `RPAREN_LEVELS[i]`.
 */
object FlowableExprTokenTypes {
    /** Number of distinct paren colours; nesting depth is taken modulo this. */
    const val PAREN_LEVELS = 5

    val LPAREN_LEVELS: Array<IElementType> = Array(PAREN_LEVELS) { t("FLW_EXPR_LPAREN_$it") }
    val RPAREN_LEVELS: Array<IElementType> = Array(PAREN_LEVELS) { t("FLW_EXPR_RPAREN_$it") }

    // Level-0 aliases, used where nesting depth is irrelevant (the of() fallback below).
    val LPAREN = LPAREN_LEVELS[0]
    val RPAREN = RPAREN_LEVELS[0]

    val IDENTIFIER = t("FLW_EXPR_IDENTIFIER")
    val NUMBER = t("FLW_EXPR_NUMBER")
    val STRING = t("FLW_EXPR_STRING")
    val BAD_STRING = t("FLW_EXPR_BAD_STRING")
    val DOT = t("FLW_EXPR_DOT")
    val COLON = t("FLW_EXPR_COLON")
    val COMMA = t("FLW_EXPR_COMMA")
    val LBRACKET = t("FLW_EXPR_LBRACKET")
    val RBRACKET = t("FLW_EXPR_RBRACKET")
    val PIPE = t("FLW_EXPR_PIPE")
    val OPERATOR = t("FLW_EXPR_OPERATOR")
    val BAD = t("FLW_EXPR_BAD")

    /** Paren token for colour [level] (opener/closer chosen by [opening]). */
    fun paren(opening: Boolean, level: Int): IElementType {
        val l = ((level % PAREN_LEVELS) + PAREN_LEVELS) % PAREN_LEVELS
        return if (opening) LPAREN_LEVELS[l] else RPAREN_LEVELS[l]
    }

    /** Maps a tokenizer [TokType] to its editor type. Parens fall back to level 0 — the highlighting
     *  lexer assigns the real level directly via [paren] and never routes parens through here. */
    fun of(type: TokType): IElementType = when (type) {
        TokType.IDENT -> IDENTIFIER
        TokType.NUMBER -> NUMBER
        TokType.STRING -> STRING
        TokType.STRING_BAD -> BAD_STRING
        TokType.DOT -> DOT
        TokType.COLON -> COLON
        TokType.COMMA -> COMMA
        TokType.LPAREN -> LPAREN
        TokType.RPAREN -> RPAREN
        TokType.LBRACKET -> LBRACKET
        TokType.RBRACKET -> RBRACKET
        TokType.PIPE -> PIPE
        TokType.ARROW -> OPERATOR
        TokType.OP -> OPERATOR
        TokType.BAD -> BAD
    }

    private fun t(name: String): IElementType = IElementType(name, FlowableBackendExprLanguage)
}
