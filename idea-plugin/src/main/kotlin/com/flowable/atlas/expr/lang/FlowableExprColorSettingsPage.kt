package com.flowable.atlas.expr.lang

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

/**
 * Settings → Editor → Color Scheme → Flowable Expression: makes the FLW_EXPR_* keys — most
 * importantly the five rainbow-paren levels — actually customizable. The demo text nests five
 * levels deep so every level is visible at once.
 */
class FlowableExprColorSettingsPage : ColorSettingsPage {

    override fun getDisplayName(): String = "Flowable Expression"
    override fun getIcon(): Icon? = null
    override fun getHighlighter(): SyntaxHighlighter = FlowableExprSyntaxHighlighter()
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getDemoText(): String =
        """
        vars:get('order').items[0].price * (1 + (flw.round((rate / (100 * (1 - fee))), 2)))
        status == 'open' ? flw.sum([a, b, 42.5]) : date:now()
        """.trimIndent()

    private companion object {
        val DESCRIPTORS: Array<AttributesDescriptor> = buildList {
            FlowableExprSyntaxHighlighter.PAREN_LEVEL_KEYS.forEachIndexed { i, key ->
                add(AttributesDescriptor("Parentheses//Level ${i + 1}", key))
            }
            add(AttributesDescriptor("Brackets", FlowableExprSyntaxHighlighter.BRACKETS))
            add(AttributesDescriptor("String", FlowableExprSyntaxHighlighter.STRING))
            add(AttributesDescriptor("Number", FlowableExprSyntaxHighlighter.NUMBER))
            add(AttributesDescriptor("Operator", FlowableExprSyntaxHighlighter.OPERATOR))
            add(AttributesDescriptor("Dot", FlowableExprSyntaxHighlighter.DOT))
            add(AttributesDescriptor("Comma", FlowableExprSyntaxHighlighter.COMMA))
            add(AttributesDescriptor("Identifier", FlowableExprSyntaxHighlighter.IDENTIFIER))
        }.toTypedArray()
    }
}
