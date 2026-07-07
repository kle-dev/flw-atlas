package com.flowable.atlas.expr.annotator

import com.flowable.atlas.expr.ParenLeveling
import com.flowable.atlas.expr.lang.FlowableExprFile
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import java.awt.Color
import java.awt.Font

/**
 * Rainbow parentheses: each nesting level of round `(` `)` is coloured differently, so a matching
 * pair shares a colour and it's easy to see how the parentheses nest and whether they close. Applies
 * to the playground field and every injected `${…}` / `{{…}}` fragment. Colours are registered
 * [TextAttributesKey]s (customizable under Settings → Editor → Color Scheme).
 */
class FlowableExprRainbowParenAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is FlowableExprFile) return
        for (p in ParenLeveling.levels(element.text)) {
            val key = LEVEL_KEYS[p.level % LEVEL_KEYS.size]
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(p.start, p.end))
                .textAttributes(key)
                .create()
        }
    }

    companion object {
        private fun level(name: String, rgb: Int): TextAttributesKey =
            TextAttributesKey.createTextAttributesKey(
                name,
                TextAttributes(Color(rgb), null, null, null, Font.PLAIN),
            )

        // Five distinguishable hues that read on both light and dark backgrounds; cycled by depth.
        private val LEVEL_KEYS: Array<TextAttributesKey> = arrayOf(
            level("FLW_EXPR_PAREN_L0", 0x3592C4), // blue
            level("FLW_EXPR_PAREN_L1", 0xE8A33D), // orange
            level("FLW_EXPR_PAREN_L2", 0x59A869), // green
            level("FLW_EXPR_PAREN_L3", 0xC670C6), // magenta
            level("FLW_EXPR_PAREN_L4", 0xD1655A), // red
        )
    }
}
