package com.flowable.atlas.script.toolwindow

import com.flowable.atlas.parsing.ScriptVarUse
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The info strip under the script editor: which variables the script touches and which objects the
 * selected context binds. Two-column layout — a narrow right-aligned label column and a chip area
 * that *wraps* instead of clipping — with soft pill chips and a click-to-expand overflow ("+N more"
 * / "show less") per row. Same evidence vocabulary as everywhere in Atlas: API variable access is
 * as good as a declaration, `≈` marks a heuristic bare-identifier read. Clicking a chip hands its
 * name to [onPick] — the panel inserts it into the script at the caret.
 */
internal class ScriptVarChipsPanel(private val onPick: ((String) -> Unit)? = null) : JPanel(GridBagLayout()) {

    private companion object {
        const val COLLAPSED_CHIP_COUNT = 12
        // soft theme-aware pill tints (light, dark)
        val VAR_BG = JBColor(0xDCEBFA, 0x2C3F55)
        val READ_BG = JBColor(0xEDEDED, 0x3A3D40)
        val BINDING_BG = JBColor(0xE8E3F7, 0x3B3450)
        val BEAN_BG = JBColor(0xE3F1E5, 0x2F4436)
    }

    private data class Row(
        val label: String, val names: List<String>, val bg: JBColor,
        val prefix: String, val muted: Boolean, val tip: String,
    )

    private var rows: List<Row> = emptyList()
    private val expanded = HashSet<String>()

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 2, 2, 2)
        isVisible = false
        // wrapped rows change their preferred height with the available width
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) { revalidate() }
        })
    }

    /** EDT-only, called from the diagnostics apply pass so chips and squiggles never drift apart.
     *  [bindings] are the engine root objects the selected context binds, [beans] the catalogued
     *  Work platform beans. */
    fun setVars(vars: ScriptVarUse, bindings: List<String> = emptyList(), beans: List<String> = emptyList()) {
        val next = ArrayList<Row>()
        if (vars.api.isNotEmpty()) next += Row("Variables", vars.api.sorted(), VAR_BG, "", false,
            "Read/written via the Flowable API (setVariable, flw.getInput, …) — as good as a declaration")
        if (vars.reads.isNotEmpty()) next += Row("Reads", vars.reads.sorted(), READ_BG, "≈ ", true,
            "Heuristic: a bare identifier that likely reads a scope variable")
        if (bindings.isNotEmpty()) next += Row("Bindings", bindings, BINDING_BG, "", false,
            "Root object Flowable binds into this script context (plus process/case variables " +
                "and Spring beans by name)")
        if (beans.isNotEmpty()) next += Row("Beans", beans, BEAN_BG, "", false,
            "Flowable Work platform service — scripts resolve any Spring bean by name " +
                "(unavailable under sandbox strict-mode)")
        if (next == rows) return
        rows = next
        expanded.retainAll(next.map { it.label }.toSet())
        rebuild()
    }

    private fun rebuild() {
        removeAll()
        for ((index, row) in rows.withIndex()) {
            val label = JBLabel(row.label, SwingConstants.RIGHT).apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBColor.GRAY
            }
            add(label, GridBagConstraints().apply {
                gridx = 0; gridy = index
                anchor = GridBagConstraints.FIRST_LINE_END
                insets = JBUI.insets(5, 0, 0, 8)
            })
            add(chipFlow(row), GridBagConstraints().apply {
                gridx = 1; gridy = index
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.FIRST_LINE_START
            })
        }
        isVisible = componentCount > 0
        revalidate()
        repaint()
    }

    private fun chipFlow(row: Row): JPanel = JPanel(WrapLayout(JBUI.scale(4), JBUI.scale(4))).apply {
        isOpaque = false
        val showAll = row.label in expanded
        val visible = if (showAll) row.names else row.names.take(COLLAPSED_CHIP_COUNT)
        for (name in visible) {
            add(Chip(row.prefix + name, row.bg, row.muted, row.tip, onPick?.let { pick -> { pick(name) } }))
        }
        val hiddenCount = row.names.size - visible.size
        if (hiddenCount > 0 || showAll && row.names.size > COLLAPSED_CHIP_COUNT) {
            add(ActionLink(if (showAll) "show less" else "+$hiddenCount more") {
                if (!expanded.remove(row.label)) expanded.add(row.label)
                rebuild()
            }.apply { font = JBUI.Fonts.smallFont() })
        }
    }

    /** A soft rounded pill — no hard 1px frame, just a tinted capsule that adapts to the theme. */
    private class Chip(
        text: String, private val bg: JBColor, muted: Boolean, tip: String, onClick: (() -> Unit)?,
    ) : JBLabel(text) {
        init {
            isOpaque = false
            font = JBUI.Fonts.smallFont()
            border = JBUI.Borders.empty(2, 8)
            toolTipText = if (onClick != null) "$tip — click to insert into the script" else tip
            if (muted) foreground = JBColor.GRAY
            if (onClick != null) {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = onClick()
                })
            }
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = bg
            g2.fillRoundRect(0, 0, width, height, height, height)
            g2.dispose()
            super.paintComponent(g)
        }
    }

    /**
     * A FlowLayout that wraps within the parent's width and reports the wrapped height as its
     * preferred size — the classic "WrapLayout"; the platform ships no wrapping layout of its own.
     */
    private class WrapLayout(hgap: Int, vgap: Int) : FlowLayout(LEFT, hgap, vgap) {

        override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, preferred = true)

        override fun minimumLayoutSize(target: Container): Dimension =
            layoutSize(target, preferred = false).also { it.width -= hgap + 1 }

        private fun layoutSize(target: Container, preferred: Boolean): Dimension {
            synchronized(target.treeLock) {
                var maxWidth = target.width
                if (maxWidth == 0) maxWidth = Int.MAX_VALUE
                val insets = target.insets
                val available = maxWidth - insets.left - insets.right - hgap * 2
                var rowWidth = 0
                var rowHeight = 0
                val total = Dimension(0, 0)
                for (i in 0 until target.componentCount) {
                    val c = target.getComponent(i)
                    if (!c.isVisible) continue
                    val d = if (preferred) c.preferredSize else c.minimumSize
                    if (rowWidth + d.width > available && rowWidth > 0) {
                        total.width = maxOf(total.width, rowWidth)
                        total.height += rowHeight + vgap
                        rowWidth = 0
                        rowHeight = 0
                    }
                    rowWidth += d.width + hgap
                    rowHeight = maxOf(rowHeight, d.height)
                }
                total.width = maxOf(total.width, rowWidth)
                total.height += rowHeight
                total.width += insets.left + insets.right + hgap * 2
                total.height += insets.top + insets.bottom + vgap * 2
                return total
            }
        }
    }
}
