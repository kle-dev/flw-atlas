package com.flowable.atlas.expr.lang

import com.intellij.psi.tree.IElementType

/**
 * Token element types used by the editor highlighter + brace matcher (NOT by the single-leaf PSI
 * parser). Shared by both dialects — coloring and brace matching key off the token identity, so the
 * types are declared once (nominally under the backend language).
 */
object FlowableExprTokenTypes {
    val IDENTIFIER = t("FLW_EXPR_IDENTIFIER")
    val NUMBER = t("FLW_EXPR_NUMBER")
    val STRING = t("FLW_EXPR_STRING")
    val BAD_STRING = t("FLW_EXPR_BAD_STRING")
    val DOT = t("FLW_EXPR_DOT")
    val COLON = t("FLW_EXPR_COLON")
    val COMMA = t("FLW_EXPR_COMMA")
    val LPAREN = t("FLW_EXPR_LPAREN")
    val RPAREN = t("FLW_EXPR_RPAREN")
    val LBRACKET = t("FLW_EXPR_LBRACKET")
    val RBRACKET = t("FLW_EXPR_RBRACKET")
    val PIPE = t("FLW_EXPR_PIPE")
    val OPERATOR = t("FLW_EXPR_OPERATOR")
    val BAD = t("FLW_EXPR_BAD")

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
