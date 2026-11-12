package com.flowable.atlas.playground

import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter

/** One finding to paint: a range in the editor's document, its message, and whether it is an error. */
data class PlaygroundProblem(val startOffset: Int, val endOffset: Int, val message: String, val isError: Boolean)

/**
 * Paints a playground's findings into its editor with the **editor colour scheme's** error and warning
 * attributes — the same wave, the same colours, the same error stripe a Java file gets.
 *
 * The two playgrounds used to paint their own: a bold underline in four hard-coded hex colours, with a
 * comment saying the scheme's wave was too thin at the field's font size. It was — because the field
 * rendered in the Swing label font, not the editor's (see [PlaygroundEditors]). With that fixed, the
 * scheme's attributes are the right ones: a user who tuned *Errors and Warnings* gets their colours
 * here too, and a key-based highlighter re-resolves on a scheme switch without a repaint.
 *
 * The markup dies with the editor it was painted on; [attach] forgets it when a new editor materialises.
 */
class PlaygroundMarkup {

    private var editor: EditorEx? = null
    private val highlighters = ArrayList<RangeHighlighter>()

    fun attach(editor: EditorEx) {
        highlighters.clear()
        this.editor = editor
    }

    fun paint(problems: List<PlaygroundProblem>) {
        clear()
        val editor = editor?.takeUnless { it.isDisposed } ?: return
        val length = editor.document.textLength
        if (length == 0) return
        for (p in problems) {
            var start = p.startOffset.coerceIn(0, length)
            var end = p.endOffset.coerceIn(0, length)
            // A zero-length finding (the unclosed '(') gets two characters so the wave has a shape.
            if (end - start < 2) {
                end = (start + 2).coerceAtMost(length)
                start = (end - 2).coerceAtLeast(0)
            }
            if (start >= end) continue
            val key = if (p.isError) CodeInsightColors.ERRORS_ATTRIBUTES else CodeInsightColors.WARNINGS_ATTRIBUTES
            val layer = if (p.isError) HighlighterLayer.ERROR else HighlighterLayer.WARNING
            val h = editor.markupModel.addRangeHighlighter(key, start, end, layer, HighlighterTargetArea.EXACT_RANGE)
            h.errorStripeTooltip = p.message
            highlighters += h
        }
    }

    fun clear() {
        editor?.takeUnless { it.isDisposed }?.let { e ->
            for (h in highlighters) if (h.isValid) e.markupModel.removeHighlighter(h)
        }
        highlighters.clear()
    }
}
