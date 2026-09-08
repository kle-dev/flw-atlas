package com.flowable.atlas.playground

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * What came out: one box with explicit states, so "valid but not previewable" and "still evaluating"
 * never read like failures. Colours come from the theme (`Label.errorForeground`, the context-help
 * grey), not from `JBColor.RED`.
 */
class PlaygroundResultPane(private var emptyHint: String) : JPanel(BorderLayout()) {

    sealed interface ResultState {
        data class Empty(val hint: String) : ResultState
        data class Loading(val label: String) : ResultState
        data class Ok(val value: String) : ResultState
        data class Error(val message: String) : ResultState
        data class Info(val message: String) : ResultState
    }

    var state: ResultState = ResultState.Empty(emptyHint)
        private set

    private val caption = JBLabel("Result").apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(0, 2, 3, 0)
    }
    private val icon = JBLabel().apply {
        verticalAlignment = SwingConstants.TOP
        border = JBUI.Borders.empty(6, 2, 0, 6)
    }
    private val text = JBTextArea(4, 40).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 6, 6, 6)
        // keep a few lines visible so the splitter's honoured minimum can't collapse the result away
        minimumSize = Dimension(JBUI.scale(80), JBUI.scale(72))
        add(caption, BorderLayout.NORTH)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(icon, BorderLayout.WEST)
            add(JBScrollPane(text), BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        show(state)
    }

    /** What the empty state says — the two dialects have different next steps. */
    fun setEmptyHint(hint: String) {
        emptyHint = hint
        if (state is ResultState.Empty) showEmpty()
    }

    fun showOk(value: String) = show(ResultState.Ok(value))
    fun showError(message: String) = show(ResultState.Error(message))
    fun showInfo(message: String) = show(ResultState.Info(message))
    fun showLoading(label: String = "Evaluating…") = show(ResultState.Loading(label))
    fun showEmpty() = show(ResultState.Empty(emptyHint))

    fun show(next: ResultState) {
        state = next
        val grey = UIUtil.getContextHelpForeground()
        val (i: Icon?, message: String, color: Color) = when (next) {
            is ResultState.Ok -> Triple(AllIcons.General.InspectionsOK, next.value, JBColor.foreground())
            is ResultState.Error -> Triple(AllIcons.General.Error, next.message, JBUI.CurrentTheme.Label.errorForeground())
            is ResultState.Info -> Triple(AllIcons.General.Information, next.message, grey)
            is ResultState.Loading -> Triple(AnimatedIcon.Default.INSTANCE, next.label, grey)
            is ResultState.Empty -> Triple(null, next.hint, grey)
        }
        icon.icon = i
        text.foreground = color
        text.text = message
        text.caretPosition = 0
    }
}
