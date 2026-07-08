package com.flowable.atlas.expr.lang

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import java.awt.Color
import java.awt.Font

/**
 * Colors expression tokens — parentheses, brackets, strings, numbers, operators — in the playground
 * field and in every injected `${…}` / `{{…}}` fragment. Combined with [FlowableExprBraceMatcher]
 * this gives the visual "do the parentheses close?" feedback.
 *
 * **Rainbow parentheses** are done here (not via an annotator): the highlighting lexer tags each
 * round paren with its nesting level, and each level maps to a distinct colour below. Because this is
 * the base syntax-highlighting layer, it paints reliably everywhere — crucially in the playground's
 * embedded editor field, where annotator/daemon highlights do not. A matching `(` / `)` share a level
 * and therefore a colour.
 */
class FlowableExprSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = FlowableExprHighlightingLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> =
        KEYS[tokenType] ?: EMPTY

    companion object {
        private val EMPTY = emptyArray<TextAttributesKey>()

        private fun key(name: String, fallback: TextAttributesKey) =
            TextAttributesKey.createTextAttributesKey(name, fallback)

        // Five distinguishable hues that read on both light and dark backgrounds; cycled by paren
        // depth. Custom colours (with an in-code default) — customizable under Settings → Editor →
        // Color Scheme → Flowable expression.
        private val PAREN_COLORS = intArrayOf(0x3592C4, 0xE8A33D, 0x59A869, 0xC670C6, 0xD1655A)
        private val PAREN_LEVEL_KEYS: Array<TextAttributesKey> = Array(PAREN_COLORS.size) { i ->
            TextAttributesKey.createTextAttributesKey(
                "FLW_EXPR_PAREN_L$i",
                TextAttributes(Color(PAREN_COLORS[i]), null, null, null, Font.PLAIN),
            )
        }

        val BRACKETS = key("FLW_EXPR_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
        val STRING = key("FLW_EXPR_STRING_LIT", DefaultLanguageHighlighterColors.STRING)
        val NUMBER = key("FLW_EXPR_NUMBER_LIT", DefaultLanguageHighlighterColors.NUMBER)
        val OPERATOR = key("FLW_EXPR_OPERATOR_SIGN", DefaultLanguageHighlighterColors.OPERATION_SIGN)
        val DOT = key("FLW_EXPR_DOT_SIGN", DefaultLanguageHighlighterColors.DOT)
        val COMMA = key("FLW_EXPR_COMMA_SIGN", DefaultLanguageHighlighterColors.COMMA)
        val IDENTIFIER = key("FLW_EXPR_IDENT", DefaultLanguageHighlighterColors.IDENTIFIER)
        val BAD = HighlighterColors.BAD_CHARACTER

        private val KEYS: Map<IElementType, Array<TextAttributesKey>> = buildMap {
            FlowableExprTokenTypes.LPAREN_LEVELS.forEachIndexed { i, t -> put(t, arrayOf(PAREN_LEVEL_KEYS[i])) }
            FlowableExprTokenTypes.RPAREN_LEVELS.forEachIndexed { i, t -> put(t, arrayOf(PAREN_LEVEL_KEYS[i])) }
            put(FlowableExprTokenTypes.LBRACKET, arrayOf(BRACKETS))
            put(FlowableExprTokenTypes.RBRACKET, arrayOf(BRACKETS))
            put(FlowableExprTokenTypes.STRING, arrayOf(STRING))
            put(FlowableExprTokenTypes.BAD_STRING, arrayOf(STRING))
            put(FlowableExprTokenTypes.NUMBER, arrayOf(NUMBER))
            put(FlowableExprTokenTypes.OPERATOR, arrayOf(OPERATOR))
            put(FlowableExprTokenTypes.PIPE, arrayOf(OPERATOR))
            put(FlowableExprTokenTypes.COLON, arrayOf(OPERATOR))
            put(FlowableExprTokenTypes.DOT, arrayOf(DOT))
            put(FlowableExprTokenTypes.COMMA, arrayOf(COMMA))
            put(FlowableExprTokenTypes.IDENTIFIER, arrayOf(IDENTIFIER))
            put(FlowableExprTokenTypes.BAD, arrayOf(BAD))
        }
    }
}

/** Supplies [FlowableExprSyntaxHighlighter] for both dialect languages (registered in plugin.xml). */
class FlowableExprSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        FlowableExprSyntaxHighlighter()
}
