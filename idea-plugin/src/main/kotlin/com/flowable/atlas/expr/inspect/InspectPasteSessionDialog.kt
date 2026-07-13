package com.flowable.atlas.expr.inspect

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * "Paste session from browser" for the "Evaluate Against App" flow: the reliable alternative to the
 * embedded-browser login ([InspectSignInDialog]) for apps whose IdP blocks embedded webviews. The user
 * logs into the app in their **normal** browser, copies an authenticated request from DevTools
 * (Network → right-click → Copy → Copy as cURL, or the raw `Cookie` header), and pastes it here; the
 * auth-relevant headers are extracted by [CurlAuthParser] and replayed by [InspectClient].
 *
 * Pure UI — the parsing is a pure function, so no network or EDT-blocking work happens here.
 */
class InspectPasteSessionDialog(project: Project) : DialogWrapper(project) {

    private val textArea = JBTextArea(12, 70).apply {
        lineWrap = true
        wrapStyleWord = false
        border = JBUI.Borders.empty(4)
    }

    /** The parsed headers/base-URL; populated on OK. */
    var parsed: CurlAuthParser.Parsed = CurlAuthParser.parse("")
        private set

    init {
        title = "Paste session from browser"
        setOKButtonText("Use this session")
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 6)).apply {
        add(
            JBLabel(
                "<html>In your browser, signed in to the app: open <b>DevTools → Network</b>, right-click " +
                    "any request → <b>Copy → Copy as cURL</b>, and paste it below.<br>" +
                    "(Pasting just the <code>Cookie:</code> request header also works.) " +
                    "Only Cookie / Authorization / CSRF-token headers are read; nothing is stored on disk.</html>",
            ),
            BorderLayout.NORTH,
        )
        add(JBScrollPane(textArea).apply { preferredSize = Dimension(720, 260) }, BorderLayout.CENTER)
    }

    override fun getPreferredFocusedComponent(): JComponent = textArea

    override fun doOKAction() {
        parsed = CurlAuthParser.parse(textArea.text)
        super.doOKAction()
    }
}
