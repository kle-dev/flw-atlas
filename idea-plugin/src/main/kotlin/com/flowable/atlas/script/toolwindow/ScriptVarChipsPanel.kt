package com.flowable.atlas.script.toolwindow

import com.flowable.atlas.parsing.ScriptVarUse
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * The variables the script touches, as chips under the problems strip — the same evidence split as
 * everywhere else in Atlas: `api` names come from an explicit Flowable API call and are as good as
 * a declaration; `reads` are heuristic bare-identifier guesses and wear the `≈` prefix. Two capped
 * rows (no wrapping layout exists in the 2026.1 platform), hidden entirely when the script touches
 * nothing.
 */
internal class ScriptVarChipsPanel : JPanel() {

    private companion object {
        const val MAX_CHIPS_PER_ROW = 10
        val API_BG = JBColor(0xDDEBF9, 0x2D3B4E)
        val READ_BG = JBColor(0xF0F0F0, 0x3C3F41)
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 2, 0, 2)
        isVisible = false
    }

    /** EDT-only, called from the diagnostics apply pass so chips and squiggles never drift apart.
     *  [bindings] are the root objects the selected context binds (from the bindings catalog). */
    fun setVars(vars: ScriptVarUse, bindings: List<String> = emptyList()) {
        removeAll()
        if (vars.api.isNotEmpty()) {
            add(row("Variables:", vars.api.sorted(), API_BG, prefix = "",
                tip = "Read/written via the Flowable API (setVariable, flw.getInput, …) — as good as a declaration"))
        }
        if (vars.reads.isNotEmpty()) {
            add(row("Reads:", vars.reads.sorted(), READ_BG, prefix = "≈ ",
                tip = "Heuristic: a bare identifier that likely reads a scope variable"))
        }
        if (bindings.isNotEmpty()) {
            add(row("Bindings:", bindings, READ_BG, prefix = "",
                tip = "Root objects Flowable binds into this script context (plus process/case " +
                    "variables and Spring beans by name)"))
        }
        isVisible = componentCount > 0
        revalidate()
        repaint()
    }

    private fun row(label: String, names: List<String>, bg: JBColor, prefix: String, tip: String): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(2)
            add(JBLabel(label).apply { foreground = JBColor.GRAY })
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            for (name in names.take(MAX_CHIPS_PER_ROW)) {
                add(chip(prefix + name, bg, tip, muted = prefix.isNotEmpty()))
                add(Box.createHorizontalStrut(JBUI.scale(4)))
            }
            if (names.size > MAX_CHIPS_PER_ROW) {
                add(JBLabel("+${names.size - MAX_CHIPS_PER_ROW} more").apply {
                    foreground = JBColor.GRAY
                    toolTipText = names.drop(MAX_CHIPS_PER_ROW)
                        .joinToString("<br>", "<html>", "</html>") { prefix + it }
                })
            }
            add(Box.createHorizontalGlue())
        }

    private fun chip(text: String, bg: JBColor, tip: String, muted: Boolean): JBLabel =
        JBLabel(text).apply {
            isOpaque = true
            background = bg
            if (muted) foreground = JBColor.GRAY
            toolTipText = tip
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(1, 6),
            )
        }
}
