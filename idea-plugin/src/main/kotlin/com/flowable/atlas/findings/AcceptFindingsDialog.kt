package com.flowable.atlas.findings

import com.flowable.atlas.FlowableAtlasBundle.message
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/**
 * *Accept…* on findings: why they are acceptable, asked in a dialog that says how many rules it is about
 * to write and refuses an empty reason before it closes. It was `Messages.showInputDialog`, which took an
 * empty answer as a cancel and said nothing about where the reason goes.
 */
internal class AcceptFindingsDialog(project: Project, private val count: Int) : DialogWrapper(project) {

    private val reasonField = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    /** The reason as typed, trimmed — read after [showAndGet]. */
    val reason: String get() = reasonField.text.trim()

    init {
        title = message("findings.accept.title")
        setOKButtonText(message("findings.accept.ok"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label(message("findings.accept.question", count)) }
        row {
            scrollCell(reasonField).align(AlignX.FILL)
                .comment(message("findings.accept.comment"))
        }
    }.apply { preferredSize = JBUI.size(460, 170) }

    override fun getPreferredFocusedComponent(): JComponent = reasonField

    override fun doValidate(): ValidationInfo? =
        if (reason.isEmpty()) ValidationInfo(message("findings.accept.empty"), reasonField) else null

    /** For tests: type into the dialog and ask whether it would close. */
    internal fun validateWith(text: String): ValidationInfo? {
        reasonField.text = text
        return doValidate()
    }
}
