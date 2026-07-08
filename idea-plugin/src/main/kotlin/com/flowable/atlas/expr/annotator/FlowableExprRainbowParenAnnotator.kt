package com.flowable.atlas.expr.annotator

import com.flowable.atlas.expr.ParenLeveling
import com.flowable.atlas.expr.lang.FlowableExprFile
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import java.awt.Color
import java.awt.Font

/**
 * Rainbow parentheses: each nesting level of round `(` `)` is coloured differently, so a matching
 * pair shares a colour and it's easy to see how the parentheses nest and whether one is missing.
 * Applies to the playground field and every injected `${…}` / `{{…}}` fragment.
 *
 * The colour is applied with [com.intellij.lang.annotation.AnnotationBuilder.enforcedTextAttributes]
 * — i.e. the concrete [TextAttributes] are forced onto the annotation — rather than through a
 * registered [com.intellij.openapi.editor.colors.TextAttributesKey]. The key route relied on the
 * `createTextAttributesKey(name, defaultAttributes)` overload with no backing colour-scheme entry;
 * that stopped rendering after a platform upgrade (the parentheses fell back to the highlighter's
 * single flat paren colour — "the colours are gone"). Forcing the attributes is version-proof and
 * renders identically in the standalone field and in injected fragments.
 */
class FlowableExprRainbowParenAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is FlowableExprFile) return
        for (p in ParenLeveling.levels(element.text)) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(p.start, p.end))
                .enforcedTextAttributes(LEVEL_ATTRS[p.level % LEVEL_ATTRS.size])
                .create()
        }
    }

    companion object {
        // Five distinguishable hues that read on both light and dark backgrounds; cycled by depth.
        private val LEVEL_COLORS = intArrayOf(
            0x3592C4, // blue
            0xE8A33D, // orange
            0x59A869, // green
            0xC670C6, // magenta
            0xD1655A, // red
        )

        /** One immutable [TextAttributes] per level colour, reused across annotations. */
        val LEVEL_ATTRS: Array<TextAttributes> =
            LEVEL_COLORS.map { TextAttributes(Color(it), null, null, null, Font.PLAIN) }.toTypedArray()
    }
}
