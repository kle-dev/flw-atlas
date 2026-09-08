package com.flowable.atlas.playground

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * What the code runs against: one summary line that is always there — *QA (project) · Case instance
 * CAS-4711*, *payload, 14 lines, at orders[1].items[0]* — and the controls behind it, folded away once
 * they are set. The summary is what a reader glances at before pressing Evaluate; the controls are
 * what they touch once.
 */
class ContextPanel(caption: String, expandedInitially: Boolean, private val onToggle: (Boolean) -> Unit) : JPanel(BorderLayout()) {

    private val captionLabel = JBLabel(caption).apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
    }
    private val summary = JBLabel()
    private val toggle = ActionLink("") { expanded = !expanded }
    private val body = JPanel(BorderLayout()).apply { isOpaque = false }

    var expanded: Boolean = expandedInitially
        set(value) {
            field = value
            body.isVisible = value
            toggle.text = if (value) "Hide" else "Details"
            onToggle(value)
            revalidate()
            repaint()
        }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 6, 2, 6)
        add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(3)
            add(captionLabel, BorderLayout.WEST)
            add(summary, BorderLayout.CENTER)
            add(toggle, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        expanded = expandedInitially
    }

    fun setCaption(text: String) { captionLabel.text = text }

    fun setSummary(text: String, icon: Icon? = null) {
        summary.text = text
        summary.icon = icon
    }

    fun setBody(component: JComponent) {
        body.removeAll()
        body.add(component, BorderLayout.CENTER)
        body.revalidate()
    }

    /** For tests. */
    val summaryText: String get() = summary.text
}
