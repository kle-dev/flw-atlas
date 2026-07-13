package com.flowable.atlas.expr.toolwindow

import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.events.AtlasEventsListener
import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.expr.ExpressionScope
import com.flowable.atlas.expr.eval.EvalResult
import com.flowable.atlas.expr.inspect.InspectClient
import com.flowable.atlas.expr.inspect.InspectConnectionDetector
import com.flowable.atlas.expr.inspect.InspectCredentials
import com.flowable.atlas.expr.inspect.InspectSession
import com.flowable.atlas.expr.inspect.InspectSignInDialog
import com.flowable.atlas.expr.inspect.WorkUrlParser
import com.flowable.atlas.expr.lang.FlowableBackendExprFileType
import com.flowable.atlas.expr.lang.FlowableExprFileType
import com.flowable.atlas.expr.lang.FlowableFrontendExprFileType
import com.flowable.atlas.expr.lang.languageOf
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.index.ModelEntry
import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.icons.AllIcons
import com.intellij.json.JsonLanguage
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.LanguageTextField
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.SingleAlarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The Expression Playground: type an expression *body* (delimiters optional), pick Backend or
 * Frontend in the toolbar, and get live validation (squiggles painted straight onto the editor's
 * markup model — see [PlaygroundDiagnostics]), completion, and evaluation:
 *  - **Frontend**: evaluated live against the pasted JSON payload, with inline `= value` hints on
 *    sub-expressions (toggleable in the toolbar) and evaluate-on-select.
 *  - **Backend**: the toolbar's "Evaluate Against App" posts the expression to a running app via
 *    the Flowable Inspect REST API (needs a live process/case/task instance id).
 *
 * State (last expression, dialect, payload, scope) persists per user in workspace.xml
 * ([FlowableExprPlaygroundState]); the Inspect connection lives in the project settings, and the
 * credentials go to the IDE PasswordSafe after a successful evaluation ([InspectCredentials], same
 * scheme as the Design connection). Hosted both as the "Flowable Expressions" tool window and as
 * an extra editor tab on `*.explorer.html` files.
 */
class FlowableExpressionPanel(val project: Project) :
    SimpleToolWindowPanel(true, true), Disposable {

    data class ScopeItem(val key: String?, val label: String)

    private val state = FlowableExprPlaygroundState.getInstance(project)
    private val settings = FlowableAtlasProjectSettings.getInstance(project)

    var dialect: ExpressionDialect = state.dialect
        private set

    // -- expression editor + diagnostics ------------------------------------------------------

    private val field: LanguageTextField = LanguageTextField(languageOf(state.dialect), project, state.expression, false).apply {
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
            diagnostics.editorAvailable(editor as EditorEx)
        }
        addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                state.expression = text
                diagnostics.scheduleRevalidate()
            }
        })
    }

    private val strip = PlaygroundProblemsStrip()
    private val wrapperHint = JBLabel().apply {
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(3, 2, 0, 2)
    }
    private val diagnostics: PlaygroundDiagnostics = PlaygroundDiagnostics(
        project, field, strip,
        object : PlaygroundDiagnostics.Host {
            override val dialect: ExpressionDialect get() = this@FlowableExpressionPanel.dialect
            override val payloadText: String get() = payloadField.text
            override val showSubEvaluations: Boolean get() = state.showSubEvaluations
            override fun onFrontendResult(result: EvalResult?) = this@FlowableExpressionPanel.onFrontendResult(result)
        },
        this,
    )

    // -- frontend card -------------------------------------------------------------------------

    private val payloadField = LanguageTextField(JsonLanguage.INSTANCE, project, state.payload, false).apply {
        border = JBUI.Borders.customLine(JBColor.border(), 1)
        addSettingsProvider { editor ->
            editor.setVerticalScrollbarVisible(true)
            editor.setBorder(JBUI.Borders.empty(4))
            editor.settings.apply {
                isLineNumbersShown = false
                isFoldingOutlineShown = false
                isLineMarkerAreaShown = false
                isCaretRowShown = false
            }
        }
        addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                state.payload = text
                if (dialect == ExpressionDialect.FRONTEND) diagnostics.scheduleRevalidate()
            }
        })
    }
    private val frontendResultPane = PlaygroundResultPane("Type an expression to evaluate")

    // -- backend (Inspect) card ------------------------------------------------------------------

    /** Paste-a-Work-URL field: its content is parsed into base URL / scope / instance id below. */
    private val appUrlField = JBTextField()
    private val baseUrlField = JBTextField(settings.inspectBaseUrl)
    private val usernameField = JBTextField(settings.inspectUsername, 12)
    private val passwordField = JBPasswordField()
    private val scopeTypeCombo = ComboBox(InspectClient.ScopeType.entries.toTypedArray()).apply {
        selectedItem = state.inspectScopeType
        addActionListener { (selectedItem as? InspectClient.ScopeType)?.let { state.inspectScopeType = it } }
    }
    private val scopeIdField = JBTextField(state.inspectScopeId, 16).apply {
        document.addDocumentListener(object : javax.swing.event.DocumentListener {
            private fun sync() { state.inspectScopeId = text }
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = sync()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = sync()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = sync()
        })
    }
    private val subScopeIdField = JBTextField(16)
    private val backendResultPane = PlaygroundResultPane("Fill in the connection and evaluate against the running app")

    var isEvaluating: Boolean = false
        private set
    val canEvaluateAgainstApp: Boolean
        get() = baseUrlField.text.isNotBlank() && scopeIdField.text.isNotBlank()

    // -- scope picker -----------------------------------------------------------------------------

    @Volatile private var scopeItems: List<ScopeItem> = listOf(ScopeItem(null, ALL_VARIABLES_LABEL))
    private var currentScope: ScopeItem = scopeItems.first()
    private var pendingScopeKey: String? = state.scopeKey
    private val scopeAlarm = SingleAlarm(::reloadScopeItems, 300, this)

    /** Debounces parsing of [appUrlField] so a paste fills the form once, not per keystroke. */
    private val urlAlarm = SingleAlarm(::applyUrlFromField, 200, this)

    private val cards = JPanel(CardLayout())

    var showSubEvaluations: Boolean
        get() = state.showSubEvaluations
        set(value) {
            state.showSubEvaluations = value
            diagnostics.scheduleRevalidate()
        }

    init {
        toolbar = ActionManager.getInstance()
            .createActionToolbar("FlowableExprPlayground", buildToolbarGroup(), true)
            .also { it.targetComponent = this }
            .component

        cards.add(frontendCard(), ExpressionDialect.FRONTEND.name)
        cards.add(backendCard(), ExpressionDialect.BACKEND.name)
        cards.border = JBUI.Borders.empty(0, 6, 4, 6)

        val editorSection = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 6, 2, 6)
            add(field, BorderLayout.CENTER)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(wrapperHint)
                add(strip)
            }, BorderLayout.SOUTH)
        }

        val splitter = OnePixelSplitter(true, "flowable.atlas.expr.playground.splitter", 0.45f).apply {
            firstComponent = editorSection
            secondComponent = cards
        }
        setContent(splitter)

        project.messageBus.connect(this).subscribe(AtlasEvents.TOPIC, object : AtlasEventsListener {
            override fun modelIndexUpdated() = scopeAlarm.cancelAndRequest()
        })

        reloadScopeItems()
        updateWrapperHint()
        applyScope()
        showCard()
        prefillConnection()
        installUrlAutoParse()
        diagnostics.scheduleRevalidate()
    }

    override fun dispose() {}

    /** For hosts that hand focus to the panel — the expression editor is the natural target. */
    val focusComponent: JComponent get() = this.field

    // ---- toolbar --------------------------------------------------------------------------------

    private fun buildToolbarGroup(): DefaultActionGroup {
        val group = DefaultActionGroup()
        group.add(DialectToggleAction(this, ExpressionDialect.BACKEND))
        group.add(DialectToggleAction(this, ExpressionDialect.FRONTEND))
        group.addSeparator()
        group.add(ScopeComboBoxAction(this))
        group.addSeparator()
        group.add(EvaluateAgainstAppAction(this))
        group.add(ShowSubEvaluationsToggle(this))
        group.addSeparator()
        group.add(PlaygroundSettingsGroup(this))
        return group
    }

    // ---- dialect ----------------------------------------------------------------------------------

    /** Swap language + file type on the ONE editor field — no field re-creation; the field re-installs
     *  document listeners and re-runs the settings provider (which re-wires [diagnostics]) itself. */
    fun switchDialect(newDialect: ExpressionDialect) {
        if (newDialect == dialect) return
        dialect = newDialect
        state.dialect = newDialect
        val document = LanguageTextField.createDocument(
            field.text, languageOf(newDialect), project, LanguageTextField.SimpleDocumentCreator(),
        )
        field.setNewDocumentAndFileType(fileTypeOf(newDialect), document)
        updateWrapperHint()
        applyScope()
        reloadScopeItems()
        showCard()
        diagnostics.scheduleRevalidate()
    }

    private fun fileTypeOf(dialect: ExpressionDialect): FlowableExprFileType =
        if (dialect == ExpressionDialect.BACKEND) FlowableBackendExprFileType else FlowableFrontendExprFileType

    private fun showCard() = (cards.layout as CardLayout).show(cards, dialect.name)

    private fun updateWrapperHint() {
        wrapperHint.text = "Evaluated as ${dialect.open} <expression> ${dialect.close} — the ${dialect.open}${dialect.close} delimiters are optional here"
    }

    /** Open the playground pre-filled (used by the Alt+Enter intention on injected fragments). */
    fun openWithExpression(text: String, dialect: ExpressionDialect, scopeKey: String? = null) {
        switchDialect(dialect)
        field.text = text
        pendingScopeKey = scopeKey
        scopeItems.firstOrNull { it.key == scopeKey }?.let { selectScope(it) }
        diagnostics.scheduleRevalidate()
        IdeFocusManager.getInstance(project).requestFocus(field, true)
    }

    // ---- frontend result -------------------------------------------------------------------------

    private fun onFrontendResult(result: EvalResult?) {
        when (result) {
            null -> frontendResultPane.showEmpty()
            is EvalResult.Ok -> frontendResultPane.showOk(renderValue(result.value))
            is EvalResult.Err -> frontendResultPane.showError(result.message)
            // valid, just not previewable statically — neutral, never reads as an invalid expression
            is EvalResult.Unavailable -> frontendResultPane.showInfo(result.message)
        }
    }

    private fun renderValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"$value\"   (string)"
        is Double -> MiniJson.stringify(value) + "   (number)"
        is Boolean -> "$value   (boolean)"
        is Map<*, *>, is List<*> -> MiniJson.stringify(value, 2)
        else -> value.toString()
    }

    // ---- cards ------------------------------------------------------------------------------------

    private fun frontendCard(): JComponent = panel {
        row {
            comment("Payload (JSON, optional) — the expression is evaluated live against it.")
        }
        row {
            cell(payloadField).align(Align.FILL)
        }.resizableRow()
        row {
            cell(frontendResultPane).align(AlignX.FILL)
        }
    }

    private fun backendCard(): JComponent = panel {
        row {
            comment("Evaluates against a running Flowable app (Inspect REST API) — a live process/case/task instance id is required.")
        }
        row("App URL:") {
            cell(appUrlField).align(AlignX.FILL)
                .comment("Paste a Work URL (…/flowable-work/#/work/all/case/<id>) — base URL, scope and id fill in automatically")
        }
        row("App base URL:") {
            cell(baseUrlField).align(AlignX.FILL)
            button("Detect from project") { applyDetectedConnection(force = true) }
        }
        row("Username:") {
            cell(usernameField)
            label("Password:")
            cell(passwordField).comment("Remembered in the IDE Password Safe after a successful evaluation")
        }
        row("") {
            button("Sign in via browser (SSO)…") { signInToApp() }
                .comment("For SSO/OAuth2-fronted apps: logs in in a browser and reuses the session cookie for this IDE session. Combine with the username/password above when Flowable also requires basic auth behind the SSO layer.")
        }
        row("Scope:") {
            cell(scopeTypeCombo)
            label("Instance id:")
            cell(scopeIdField).align(AlignX.FILL)
        }
        row("Sub-scope id:") {
            cell(subScopeIdField).comment("Optional")
        }
        row {
            cell(backendResultPane).align(Align.FILL)
        }.resizableRow()
    }

    // ---- scope ---------------------------------------------------------------------------------------

    fun currentScopeLabel(): String = currentScope.label
    fun scopeItemsSnapshot(): List<ScopeItem> = scopeItems

    fun selectScope(item: ScopeItem) {
        currentScope = item
        pendingScopeKey = null
        state.scopeKey = item.key
        applyScope()
    }

    /** Stamp the selected model key onto the field's PSI file so completion can scope to it. */
    private fun applyScope() {
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(field.document) ?: return
        psiFile.putUserData(ExpressionScope.MODEL_KEY, currentScope.key)
    }

    /**
     * Populates the scope items without ever building the model index on the EDT (the panel is
     * constructed synchronously when the editor tab opens — a blocking full scan here trips
     * `SlowOperations`). The cached index fills the list immediately; a cache miss loads on a
     * pooled thread and applies the items when ready.
     */
    private fun reloadScopeItems() {
        val service = project.service<FlowableModelIndexService>()
        val dialectAtRequest = dialect
        val cached = service.cachedOrNull()
        if (cached != null) {
            applyScopeItems(scopeItems(dialectAtRequest) { type -> cached.keysOfType(type) })
            return
        }
        applyScopeItems(scopeItems(dialectAtRequest) { emptyList() })   // placeholder until loaded
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val items = scopeItems(dialectAtRequest) { type -> service.keysOfType(type) }
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed && dialect == dialectAtRequest) applyScopeItems(items)
            }, ModalityState.any())
        }
    }

    private fun scopeItems(
        dialect: ExpressionDialect,
        keysOfType: (ModelType) -> List<ModelEntry>,
    ): List<ScopeItem> {
        val types = if (dialect == ExpressionDialect.FRONTEND) listOf(ModelType.FORM) else listOf(ModelType.PROCESS, ModelType.CASE)
        val items = ArrayList<ScopeItem>()
        items += ScopeItem(null, ALL_VARIABLES_LABEL)
        for (type in types) {
            for (entry in keysOfType(type)) {
                val label = if (entry.name != entry.key) "${entry.key} — ${entry.name}" else entry.key
                items += ScopeItem(entry.key, label)
            }
        }
        return items
    }

    private fun applyScopeItems(items: List<ScopeItem>) {
        scopeItems = items
        val wanted = pendingScopeKey ?: currentScope.key
        val match = items.firstOrNull { it.key == wanted } ?: items.first()
        if (match.key == pendingScopeKey) pendingScopeKey = null
        if (match != currentScope) {
            currentScope = match
            applyScope()
        }
    }

    // ---- backend (Inspect) evaluation ------------------------------------------------------------------

    /**
     * Silent prefill on open: stored PasswordSafe credentials for the configured base URL first
     * (username/password — saved by the last successful evaluation), then the project's Spring
     * config for whatever is still blank. Keychain and filename-index access both run on a pooled
     * thread (never the EDT); the result applies on the UI thread.
     */
    private fun prefillConnection() {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val stored = runCatching { InspectCredentials.load(settings.inspectBaseUrl) }.getOrNull()
            val detected = runCatching { InspectConnectionDetector.detect(project) }.getOrNull()
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                stored?.let { c ->
                    if (usernameField.text.isBlank()) c.userName?.let { usernameField.text = it }
                    if (passwordField.password.isEmpty()) c.getPasswordAsString()?.let { passwordField.text = it }
                }
                detected?.let { applyConnection(it, force = false) }
            }, ModalityState.any())
        }
    }

    /**
     * Pre-fill the Inspect connection from the project's Spring config. [force] overwrites the fields
     * (the button); otherwise only blanks are filled. Detection queries the filename index, so it runs
     * on a pooled thread (never the EDT) and applies its result on the UI thread.
     */
    private fun applyDetectedConnection(force: Boolean) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val c = runCatching { InspectConnectionDetector.detect(project) }.getOrNull() ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) applyConnection(c, force)
            }, ModalityState.any())
        }
    }

    private fun applyConnection(c: InspectConnectionDetector.Connection, force: Boolean) {
        if (!c.hasAny) {
            if (force) backendResultPane.showInfo("No Flowable app config (application*.properties) found in the project.")
            return
        }
        if (c.baseUrl != null && (force || baseUrlField.text.isBlank())) baseUrlField.text = c.baseUrl
        if (c.username != null && (force || usernameField.text.isBlank())) usernameField.text = c.username
        if (c.password != null && (force || passwordField.password.isEmpty())) passwordField.text = c.password
        if (force) {
            backendResultPane.showInfo(
                "Detected from project config: ${c.baseUrl ?: "(no base URL)"}" +
                    (c.username?.let { " · user '$it'" } ?: "") +
                    (if (c.password != null) " · password from dev config" else ""),
            )
        }
    }

    /** Wire [appUrlField] so pasting a Work URL auto-fills base URL / scope / instance id (debounced). */
    private fun installUrlAutoParse() {
        appUrlField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            private fun schedule() = urlAlarm.cancelAndRequest()
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = schedule()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = schedule()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = schedule()
        })
    }

    /**
     * Parse [appUrlField] and fill the connection fields. Only a field the URL actually yields is
     * touched: an unrecognised scope leaves the [scopeTypeCombo] on its current selection, and a
     * base-URL-only link leaves scope/id alone.
     */
    private fun applyUrlFromField() {
        if (project.isDisposed) return
        val parsed = WorkUrlParser.parse(appUrlField.text)
        if (!parsed.hasAny) return
        parsed.baseUrl?.let { baseUrlField.text = it }
        if (parsed.scopeId != null) {
            parsed.scopeType?.let { scopeTypeCombo.selectedItem = it }
            scopeIdField.text = parsed.scopeId
            subScopeIdField.text = parsed.subScopeId ?: ""
            val type = parsed.scopeType ?: scopeTypeCombo.selectedItem
            val sub = parsed.subScopeId?.let { " (sub $it)" } ?: ""
            backendResultPane.showInfo("Parsed URL → $type · ${parsed.scopeId}$sub")
        } else {
            backendResultPane.showInfo("Parsed base URL from link")
        }
    }

    /**
     * Open the embedded-browser SSO login for the configured base URL and, on success, cache the
     * captured session cookie for this IDE session ([InspectSession]) so [evaluateAgainstApp] can
     * replay it. For apps fronted by an IdP (OAuth2/SAML), where basic auth can't pass the login.
     */
    private fun signInToApp() {
        val baseUrl = baseUrlField.text.trim()
        if (baseUrl.isBlank()) {
            backendResultPane.showInfo("Enter the app base URL first, then sign in.")
            return
        }
        if (!JBCefApp.isSupported()) {
            backendResultPane.showInfo("The embedded browser (JCEF) isn't available in this IDE, so browser sign-in can't run.")
            return
        }
        val dialog = InspectSignInDialog(project, baseUrl)
        if (!dialog.showAndGet()) return
        val cookie = dialog.harvestedCookie
        if (cookie.isNullOrBlank()) {
            backendResultPane.showInfo("No session cookie was captured — make sure the login completed, then try again.")
        } else {
            InspectSession.set(baseUrl, cookie)
            backendResultPane.showInfo("Signed in — session captured for this IDE session. You can evaluate against the app now.")
        }
    }

    /** POST the expression to the configured Flowable Inspect endpoint and show the result. */
    fun evaluateAgainstApp() {
        if (isEvaluating) return
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
            cookie = InspectSession.get(baseUrlField.text.trim()),
        )
        isEvaluating = true
        backendResultPane.showLoading()
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = InspectClient.evaluate(req)
            // the app answered (auth passed) → remember the credentials for this base URL, so the
            // password only has to be typed once per app (same PasswordSafe as the Design connection)
            if (outcome is InspectClient.Outcome.Evaluated) {
                runCatching { InspectCredentials.save(req.baseUrl, req.username, req.password) }
            }
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                isEvaluating = false
                when (outcome) {
                    is InspectClient.Outcome.Evaluated -> {
                        val r = outcome.response
                        if (r.valid) {
                            backendResultPane.showOk("${MiniJson.stringify(r.value)}   (${r.valueType ?: "?"})")
                        } else {
                            backendResultPane.showError(r.exception ?: "Invalid expression")
                        }
                    }
                    is InspectClient.Outcome.Failed -> backendResultPane.showError(outcome.message)
                }
            }, ModalityState.any())
        }
    }

    companion object {
        const val TOOL_WINDOW_ID = "Flowable Expressions"
        private const val ALL_VARIABLES_LABEL = "(all variables)"

        /** Activate the playground tool window and pre-fill it — the intention's entry point. */
        fun open(project: Project, text: String, dialect: ExpressionDialect, scopeKey: String? = null) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
            toolWindow.activate({
                toolWindow.contentManager.contents
                    .firstNotNullOfOrNull { it.component as? FlowableExpressionPanel }
                    ?.openWithExpression(text, dialect, scopeKey)
            }, true)
        }
    }
}

/**
 * The evaluation-result box: an icon + read-only text with explicit Ok / Error / Info(unavailable) /
 * Loading / Empty states, so "valid but not previewable" and "loading" never read like failures.
 */
internal class PlaygroundResultPane(private val emptyHint: String) : JPanel(BorderLayout()) {

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
        add(icon, BorderLayout.WEST)
        add(JBScrollPane(text), BorderLayout.CENTER)
        showEmpty()
    }

    fun showOk(value: String) = show(AllIcons.General.InspectionsOK, value, JBColor.foreground())
    fun showError(message: String) = show(AllIcons.General.Error, message, JBColor.RED)
    fun showInfo(message: String) = show(AllIcons.General.Information, message, JBColor.GRAY)
    fun showLoading() = show(AnimatedIcon.Default.INSTANCE, "Evaluating…", JBColor.GRAY)
    fun showEmpty() = show(null, emptyHint, JBColor.GRAY)

    private fun show(i: javax.swing.Icon?, message: String, color: java.awt.Color) {
        icon.icon = i
        text.foreground = color
        text.text = message
        text.caretPosition = 0
    }
}
