package com.flowable.atlas.playground

import com.flowable.atlas.expr.ExprProblem
import com.flowable.atlas.expr.ExprSeverity
import com.intellij.icons.AllIcons
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Persistent message rows under the expression field — replaces the old monospace `^`-caret text
 * area and the orange one-line label: the exact offset is now marked by the in-editor squiggle, so
 * the strip carries the message (clicking a row jumps to the offset). Caps at [MAX_ROWS] rows plus
 * a "+N more" summary; an optional transient "Selection = …" info row sits on top, and — on a
 * Remote-Dev host, where the inline `= value` badges can't paint — the sub-expression values are
 * mirrored here as plain rows (see [setSubEvaluations]).
 */
class PlaygroundProblemsStrip : JPanel() {

    private companion object { const val MAX_ROWS = 3 }

    /** One strip row, stripped of the problem type — lets the Script Playground reuse the strip. */
    data class Row(val isError: Boolean, val message: String, val offset: Int)

    private var selectionInfo: String? = null
    private var rows: List<Row> = emptyList()
    private var subEvaluations: List<String> = emptyList()
    private var navigate: (Int) -> Unit = {}

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 2, 0, 2)
        isVisible = false
    }

    fun setProblems(problems: List<ExprProblem>, navigate: (Int) -> Unit) =
        setRows(problems.map { Row(it.severity == ExprSeverity.ERROR, it.message, it.startOffset) }, navigate)

    fun setRows(rows: List<Row>, navigate: (Int) -> Unit) {
        this.rows = rows
        this.navigate = navigate
        rebuild()
    }

    fun setSelectionInfo(text: String?) {
        if (selectionInfo == text) return
        selectionInfo = text
        rebuild()
    }

    /**
     * The `<sub-expression> = <value>` rows shown as the Remote-Dev fallback for the inline badges.
     * Only populated on a Remote-Dev host; empty everywhere else, so this stays invisible locally.
     */
    fun setSubEvaluations(rows: List<String>) {
        if (subEvaluations == rows) return
        subEvaluations = rows
        rebuild()
    }

    private fun rebuild() {
        removeAll()
        selectionInfo?.let { info ->
            add(JBLabel(info, AllIcons.General.Information, SwingConstants.LEADING).apply {
                border = JBUI.Borders.emptyBottom(2)
            })
        }
        for (r in rows.take(MAX_ROWS)) add(problemRow(r))
        if (rows.size > MAX_ROWS) {
            add(JBLabel("+${rows.size - MAX_ROWS} more").apply {
                foreground = JBColor.GRAY
                toolTipText = rows.drop(MAX_ROWS).joinToString("<br>", "<html>", "</html>") { it.message }
            })
        }
        for (row in subEvaluations) {
            add(JBLabel(row, AllIcons.General.InspectionsEye, SwingConstants.LEADING).apply {
                foreground = JBColor.GRAY
                toolTipText = row                 // full text on hover — the strip may be narrower than the row
                border = JBUI.Borders.emptyBottom(2)
            })
        }
        isVisible = componentCount > 0
        revalidate()
        repaint()
    }

    /**
     * One problem row: severity icon + the message as a hyperlink + a trailing "jump to source" icon,
     * so the row reads unmistakably as "click to go to the offending spot". The severity and jump
     * icons are their own [JBLabel]s (not the hyperlink's icon slot) so both always render.
     */
    private fun problemRow(r: Row): JPanel {
        val severityIcon = if (r.isError) AllIcons.General.Error else AllIcons.General.Warning
        val link = HyperlinkLabel(r.message).apply {
            addHyperlinkListener { navigate(r.offset) }
        }
        val jump = JBLabel(AllIcons.Actions.EditSource).apply {
            toolTipText = "Jump to the problem"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = navigate(r.offset)
            })
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(2)
            add(JBLabel(severityIcon))
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            add(link)
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            add(jump)
            add(Box.createHorizontalGlue())
        }
    }
}
