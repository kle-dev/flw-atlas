package com.flowable.atlas.expr.toolwindow

import com.flowable.atlas.expr.ExprSeverity
import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.expr.ExpressionScope
import com.flowable.atlas.expr.ExpressionValidator
import com.flowable.atlas.expr.catalog.FlowableCustomFunctions
import com.flowable.atlas.expr.eval.EvalResult
import com.flowable.atlas.expr.eval.FrontendExpressionEvaluator
import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.expr.inspect.InspectClient
import com.flowable.atlas.expr.inspect.InspectConnectionDetector
import com.flowable.atlas.expr.lang.languageOf
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.flowable.atlas.settings.FlowableAtlasSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.JBColor
import com.intellij.ui.LanguageTextField
import com.intellij.util.ui.JBUI
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.event.DocumentEvent as SwingDocumentEvent
import javax.swing.event.DocumentListener as SwingDocumentListener

/**
 * The Expression Playground: type an expression *body* (without the delimiters), pick Backend or
 * Frontend, and get live validation + completion (the field is a [LanguageTextField] bound to the
 * dialect's language, reusing the annotator + completion machinery). An optional "Scope to model"
 * picker narrows variable/field completion to one model.
 *
 * The lower panel adds the optional evaluation layers:
 *  - **Frontend**: paste a form payload as JSON → the expression is evaluated live against it
 *    (empty payload → pure syntax check).
 *  - **Backend**: "Evaluate against app" runs the expression against a running instance via the
 *    Flowable Inspect REST API (needs a live process/case/task instance id).
 */
class FlowableExpressionPanel(private val project: Project) : JPanel(BorderLayout()) {

    private var dialect = ExpressionDialect.BACKEND
    private val wrapper = JBLabel()

    // Annotator squiggles don't reliably paint in a LanguageTextField, so the playground surfaces
    // the first structural syntax error here with a caret pointing at the exact offset (e.g. the
    // unclosed '(' for a missing ')'), and semantic findings (unknown functions, dialect misuse —
    // allowlist-filtered) on the line below.
    private val syntax = JBTextArea(2, 40).apply {
        isEditable = false; isOpaque = false; foreground = JBColor.RED
        font = Font(Font.MONOSPACED, Font.PLAIN, JBLabel().font.size)
        border = BorderFactory.createEmptyBorder()
    }
    private val semantics = JBLabel().apply { foreground = JBColor.ORANGE }
    private val scopeCombo = ComboBox<ScopeItem>()
    private val center = JPanel(BorderLayout())
    private var field = createField("")

    // frontend evaluation
    private val payloadArea = JBTextArea(6, 40).apply { toolTipText = "Paste a form payload as JSON to evaluate against (optional)" }
    private val frontendResult = JBTextArea(4, 40).apply { isEditable = false }

    // backend (Inspect) evaluation
    private val settings = FlowableAtlasSettings.getInstance()
    private val baseUrlField = JBTextField(settings.inspectBaseUrl, 24)
    private val usernameField = JBTextField(settings.inspectUsername, 12)
    private val passwordField = JBPasswordField()
    private val scopeTypeCombo = ComboBox(InspectClient.ScopeType.entries.toTypedArray())
    private val scopeIdField = JBTextField(16)
    private val subScopeIdField = JBTextField(16)
    private val evalAppButton = JButton("Evaluate against app")
    private val detectButton = JButton("Detect from project")
    private val backendResult = JBTextArea(4, 40).apply { isEditable = false }

    private val cards = JPanel(CardLayout())

    private data class ScopeItem(val key: String?, val label: String) {
        override fun toString(): String = label
    }

    init {
        border = BorderFactory.createEmptyBorder(6, 8, 6, 8)

        val backend = JRadioButton(ExpressionDialect.BACKEND.display, true)
        val frontend = JRadioButton(ExpressionDialect.FRONTEND.display, false)
        ButtonGroup().apply { add(backend); add(frontend) }
        backend.addActionListener { if (backend.isSelected) switchDialect(ExpressionDialect.BACKEND) }
        frontend.addActionListener { if (frontend.isSelected) switchDialect(ExpressionDialect.FRONTEND) }
        scopeCombo.addActionListener { applyScope() }

        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JBLabel("Mode:")); add(backend); add(frontend)
            add(JBLabel("   Scope to model:")); add(scopeCombo)
        }

        cards.add(frontendCard(), ExpressionDialect.FRONTEND.name)
        cards.add(backendCard(), ExpressionDialect.BACKEND.name)

        val split = JPanel(BorderLayout())
        split.add(field, BorderLayout.NORTH)
        split.add(cards, BorderLayout.CENTER)
        center.add(split, BorderLayout.CENTER)

        add(bar, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(syntax)
            add(semantics)
            add(wrapper)
        }, BorderLayout.SOUTH)

        payloadArea.document.addDocumentListener(object : SwingDocumentListener {
            override fun insertUpdate(e: SwingDocumentEvent) = reevaluateFrontend()
            override fun removeUpdate(e: SwingDocumentEvent) = reevaluateFrontend()
            override fun changedUpdate(e: SwingDocumentEvent) = reevaluateFrontend()
        })
        evalAppButton.addActionListener { evaluateAgainstApp() }
        detectButton.addActionListener { applyDetectedConnection(force = true) }

        reloadScopeItems()
        updateWrapper()
        applyScope()
        showCard()
        reevaluateFrontend()
        updateSemanticStatus()
        applyDetectedConnection(force = false)
    }

    /**
     * Pre-fill the Inspect connection from the project's Spring config. [force] overwrites the fields
     * (the button); otherwise only blanks are filled (silent detection on open). Detection queries the
     * filename index, so it runs on a pooled thread (never the EDT) and applies its result on the UI thread.
     */
    private fun applyDetectedConnection(force: Boolean) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val c = runCatching { InspectConnectionDetector.detect(project) }.getOrNull() ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater { applyConnection(c, force) }
        }
    }

    private fun applyConnection(c: InspectConnectionDetector.Connection, force: Boolean) {
        if (!c.hasAny) {
            if (force) { backendResult.foreground = JBColor.foreground(); backendResult.text = "No Flowable app config (application*.properties) found in the project." }
            return
        }
        if (c.baseUrl != null && (force || baseUrlField.text.isBlank())) baseUrlField.text = c.baseUrl
        if (c.username != null && (force || usernameField.text.isBlank())) usernameField.text = c.username
        if (c.password != null && (force || passwordField.password.isEmpty())) passwordField.text = c.password
        if (force) {
            backendResult.foreground = JBColor.foreground()
            backendResult.text = "Detected from project config: ${c.baseUrl ?: "(no base URL)"}" +
                (c.username?.let { " · user '$it'" } ?: "") +
                (if (c.password != null) " · password from dev config" else "")
        }
    }

    private fun frontendCard(): JPanel {
        val col = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        col.add(JBLabel("Payload (JSON) — optional").apply { border = BorderFactory.createEmptyBorder(6, 0, 2, 0) })
        col.add(JBScrollPane(payloadArea).apply { preferredSize = Dimension(600, 120) })
        col.add(JBLabel("Result").apply { border = BorderFactory.createEmptyBorder(6, 0, 2, 0) })
        col.add(JBScrollPane(frontendResult).apply { preferredSize = Dimension(600, 90) })
        return JPanel(BorderLayout()).apply { add(col, BorderLayout.CENTER) }
    }

    private fun backendCard(): JPanel {
        val form = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        form.add(JBLabel("Evaluate against a running app (Flowable Inspect — live instance required)").apply {
            border = BorderFactory.createEmptyBorder(6, 0, 2, 0)
        })
        form.add(row("App base URL:", baseUrlField, detectButton))
        form.add(row("Username:", usernameField, "  Password:", passwordField))
        form.add(row("Scope:", scopeTypeCombo, "  Instance id:", scopeIdField))
        form.add(row("Sub-scope id (optional):", subScopeIdField))
        form.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 2)).apply { add(evalAppButton) })
        form.add(JBLabel("Result").apply { border = BorderFactory.createEmptyBorder(6, 0, 2, 0) })
        form.add(JBScrollPane(backendResult).apply { preferredSize = Dimension(600, 90) })
        return JPanel(BorderLayout()).apply { add(form, BorderLayout.CENTER) }
    }

    private fun row(vararg parts: Any): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
        for (p in parts) add(if (p is String) JBLabel(p) else p as javax.swing.JComponent)
    }

    private fun switchDialect(newDialect: ExpressionDialect) {
        if (newDialect == dialect) return
        val text = field.text
        val split = field.parent as JPanel
        dialect = newDialect
        split.remove(field)
        field = createField(text)
        split.add(field, BorderLayout.NORTH)
        split.revalidate(); split.repaint()
        reloadScopeItems()
        updateWrapper()
        applyScope()
        showCard()
        reevaluateFrontend()
        updateSemanticStatus()
    }

    private fun showCard() = (cards.layout as CardLayout).show(cards, dialect.name)

    /**
     * A real multi-line code editor for the expression body — a [LanguageTextField] in multi-line
     * mode with both scrollbars enabled, so long expressions scroll instead of being clipped. Reuses
     * the dialect's highlighting (incl. rainbow parens), completion and annotator.
     */
    private fun createField(text: String): LanguageTextField =
        LanguageTextField(languageOf(dialect), project, text, false).apply {
            preferredSize = Dimension(600, 160)
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            addSettingsProvider { editor ->
                editor.setVerticalScrollbarVisible(true)
                editor.setHorizontalScrollbarVisible(true)
                editor.setBorder(JBUI.Borders.empty(4))
                editor.settings.apply {
                    isLineNumbersShown = false
                    isFoldingOutlineShown = false
                    isLineMarkerAreaShown = false
                    isUseSoftWraps = false            // long expressions scroll horizontally
                    isCaretRowShown = false
                    additionalLinesCount = 1
                    additionalColumnsCount = 2
                }
            }
            addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    reevaluateFrontend()
                    updateSemanticStatus()
                }
            })
        }

    /** Semantic findings for the current text, plus the syntax-error pointer (both refreshed here
     *  since annotator squiggles don't reliably paint in the embedded field). */
    private fun updateSemanticStatus() {
        updateSyntaxStatus()
        val allowlist = FlowableAtlasProjectSettings.getInstance(project)
        val custom = FlowableCustomFunctions.getInstance(project).catalog()
        val problems = ExpressionValidator.validateSemantics(field.text, dialect, custom)
            .filterNot { allowlist.isAllowlisted(it) }
        semantics.text = problems.joinToString("   ·   ") { it.message }
        semantics.isVisible = problems.isNotEmpty()
    }

    /**
     * Show the first structural syntax error with a caret under the exact offset — e.g. for a missing
     * ')' the caret sits on the unclosed '(' so the user sees *where* to fix without hunting. A window
     * around the offset keeps it readable for long expressions; tabs/newlines become single spaces so
     * the caret stays aligned.
     */
    private fun updateSyntaxStatus() {
        val text = field.text
        val err = ExpressionValidator.validateSyntax(text, dialect)
            .firstOrNull { it.severity == ExprSeverity.ERROR }
        if (err == null) { syntax.isVisible = false; syntax.text = ""; return }
        val off = err.startOffset.coerceIn(0, text.length)
        val from = maxOf(0, off - 30)
        val to = minOf(text.length, off + 30)
        val lead = if (from > 0) "…" else ""
        val flat = text.substring(from, to).map { if (it == '\n' || it == '\t') ' ' else it }.joinToString("")
        val window = lead + flat + (if (to < text.length) "…" else "")
        val caretCol = lead.length + (off - from)
        syntax.text = window + "\n" + " ".repeat(caretCol) + "^ " + err.message
        syntax.isVisible = true
    }

    /** Frontend: evaluate the expression against the pasted payload and show the result or error. */
    private fun reevaluateFrontend() {
        if (dialect != ExpressionDialect.FRONTEND) return
        val expr = field.text
        if (expr.isBlank()) { frontendResult.foreground = JBColor.foreground(); frontendResult.text = ""; return }
        when (val r = FrontendExpressionEvaluator.evaluate(expr, payloadArea.text)) {
            is EvalResult.Ok -> { frontendResult.foreground = JBColor.foreground(); frontendResult.text = render(r.value) }
            is EvalResult.Err -> { frontendResult.foreground = JBColor.RED; frontendResult.text = r.message }
            // Valid, just not previewable statically (running-form/locale or custom externals.additionalData
            // function) — neutral gray, so it never reads as an invalid expression.
            is EvalResult.Unavailable -> { frontendResult.foreground = JBColor.GRAY; frontendResult.text = r.message }
        }
    }

    /** Backend: POST the expression to the configured Flowable Inspect endpoint and show the result. */
    private fun evaluateAgainstApp() {
        settings.inspectBaseUrl = baseUrlField.text.trim()
        settings.inspectUsername = usernameField.text.trim()
        val exprBody = field.text.trim()
        val expr = if (exprBody.startsWith("\${") || exprBody.startsWith("#{")) exprBody else "\${$exprBody}"
        val req = InspectClient.Request(
            baseUrl = baseUrlField.text.trim(),
            expression = expr,
            scopeType = scopeTypeCombo.selectedItem as InspectClient.ScopeType,
            scopeId = scopeIdField.text.trim(),
            subScopeId = subScopeIdField.text.trim().ifBlank { null },
            username = usernameField.text.trim(),
            password = String(passwordField.password),
        )
        backendResult.foreground = JBColor.foreground()
        backendResult.text = "Evaluating…"
        evalAppButton.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = InspectClient.evaluate(req)
            ApplicationManager.getApplication().invokeLater {
                evalAppButton.isEnabled = true
                when (outcome) {
                    is InspectClient.Outcome.Evaluated -> {
                        val r = outcome.response
                        if (r.valid) {
                            backendResult.foreground = JBColor.foreground()
                            backendResult.text = "${MiniJson.stringify(r.value)}   (${r.valueType ?: "?"})"
                        } else {
                            backendResult.foreground = JBColor.RED
                            backendResult.text = r.exception ?: "Invalid expression"
                        }
                    }
                    is InspectClient.Outcome.Failed -> {
                        backendResult.foreground = JBColor.RED
                        backendResult.text = outcome.message
                    }
                }
            }
        }
    }

    private fun render(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"$value\"   (string)"
        is Double -> MiniJson.stringify(value) + "   (number)"
        is Boolean -> "$value   (boolean)"
        is Map<*, *>, is List<*> -> MiniJson.stringify(value)
        else -> value.toString()
    }

    private fun updateWrapper() {
        wrapper.text = "Evaluated as ${dialect.open} <expression> ${dialect.close}  ·  the ${dialect.open}${dialect.close} delimiters are optional here"
    }

    private fun reloadScopeItems() {
        val service = project.service<FlowableModelIndexService>()
        val types = if (dialect == ExpressionDialect.FRONTEND) listOf(ModelType.FORM) else listOf(ModelType.PROCESS, ModelType.CASE)
        val items = ArrayList<ScopeItem>()
        items += ScopeItem(null, "(all variables)")
        for (type in types) {
            for (entry in service.keysOfType(type)) {
                val label = if (entry.name != entry.key) "${entry.key} — ${entry.name}" else entry.key
                items += ScopeItem(entry.key, label)
            }
        }
        scopeCombo.removeAllItems()
        items.forEach { scopeCombo.addItem(it) }
    }

    /** Stamp the selected model key onto the field's PSI file so completion can scope to it. */
    private fun applyScope() {
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(field.document) ?: return
        psiFile.putUserData(ExpressionScope.MODEL_KEY, (scopeCombo.selectedItem as? ScopeItem)?.key)
    }
}
