package com.flowable.atlas.expr.lang

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

/**
 * Colors expression tokens — parentheses, brackets, strings, numbers, operators — in the playground
 * field and in every injected `${…}` / `{{…}}` fragment. Combined with [FlowableExprBraceMatcher]
 * this gives the visual "do the parentheses close?" feedback.
 */
class FlowableExprSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = FlowableExprHighlightingLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> =
        KEYS[tokenType] ?: EMPTY

    companion object {
        private val EMPTY = emptyArray<TextAttributesKey>()

        private fun key(name: String, fallback: TextAttributesKey) =
            TextAttributesKey.createTextAttributesKey(name, fallback)

        val PARENS = key("FLW_EXPR_PARENS", DefaultLanguageHighlighterColors.PARENTHESES)
        val BRACKETS = key("FLW_EXPR_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
        val STRING = key("FLW_EXPR_STRING_LIT", DefaultLanguageHighlighterColors.STRING)
        val NUMBER = key("FLW_EXPR_NUMBER_LIT", DefaultLanguageHighlighterColors.NUMBER)
        val OPERATOR = key("FLW_EXPR_OPERATOR_SIGN", DefaultLanguageHighlighterColors.OPERATION_SIGN)
        val DOT = key("FLW_EXPR_DOT_SIGN", DefaultLanguageHighlighterColors.DOT)
        val COMMA = key("FLW_EXPR_COMMA_SIGN", DefaultLanguageHighlighterColors.COMMA)
        val IDENTIFIER = key("FLW_EXPR_IDENT", DefaultLanguageHighlighterColors.IDENTIFIER)
        val BAD = HighlighterColors.BAD_CHARACTER

        private val KEYS: Map<IElementType, Array<TextAttributesKey>> = mapOf(
            FlowableExprTokenTypes.LPAREN to arrayOf(PARENS),
            FlowableExprTokenTypes.RPAREN to arrayOf(PARENS),
            FlowableExprTokenTypes.LBRACKET to arrayOf(BRACKETS),
            FlowableExprTokenTypes.RBRACKET to arrayOf(BRACKETS),
            FlowableExprTokenTypes.STRING to arrayOf(STRING),
            FlowableExprTokenTypes.BAD_STRING to arrayOf(STRING),
            FlowableExprTokenTypes.NUMBER to arrayOf(NUMBER),
            FlowableExprTokenTypes.OPERATOR to arrayOf(OPERATOR),
            FlowableExprTokenTypes.PIPE to arrayOf(OPERATOR),
            FlowableExprTokenTypes.COLON to arrayOf(OPERATOR),
            FlowableExprTokenTypes.DOT to arrayOf(DOT),
            FlowableExprTokenTypes.COMMA to arrayOf(COMMA),
            FlowableExprTokenTypes.IDENTIFIER to arrayOf(IDENTIFIER),
            FlowableExprTokenTypes.BAD to arrayOf(BAD),
        )
    }
}

/** Supplies [FlowableExprSyntaxHighlighter] for both dialect languages (registered in plugin.xml). */
class FlowableExprSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        FlowableExprSyntaxHighlighter()
}
