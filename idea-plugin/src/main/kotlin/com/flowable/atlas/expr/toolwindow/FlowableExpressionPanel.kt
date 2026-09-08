package com.flowable.atlas.expr.toolwindow

import com.flowable.atlas.environment.AtlasCatalog
import com.flowable.atlas.environment.AtlasConnection
import com.flowable.atlas.environment.AtlasProtection
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.environment.auth.AtlasCredentials
import com.flowable.atlas.environment.auth.AuthContext
import com.flowable.atlas.environment.auth.AuthMode
import com.flowable.atlas.environment.auth.BrowserSessions
import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.events.AtlasEventsListener
import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.expr.ExpressionScope
import com.flowable.atlas.expr.eval.EvalResult
import com.flowable.atlas.expr.eval.PayloadScopePath
import com.flowable.atlas.expr.inspect.InspectClient
import com.flowable.atlas.expr.inspect.InspectSessionTargets
import com.flowable.atlas.expr.inspect.PasteWorkUrlDialog
import com.flowable.atlas.expr.inspect.SaveWorkTargetDialog
import com.flowable.atlas.expr.lang.FlowableBackendExprFileType
import com.flowable.atlas.expr.lang.FlowableExprFileType
import com.flowable.atlas.expr.lang.FlowableFrontendExprFileType
import com.flowable.atlas.expr.lang.languageOf
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.index.ModelEntry
import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.playground.ContextPanel
import com.flowable.atlas.playground.PlaygroundEditors
import com.flowable.atlas.playground.PlaygroundProblemsStrip
import com.flowable.atlas.playground.PlaygroundResultPane
import com.flowable.atlas.playground.PlaygroundShell
import com.flowable.atlas.settings.EnvironmentsConfigurable
import com.intellij.icons.AllIcons
import com.intellij.json.JsonLanguage
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.LanguageTextField
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.actionButton
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.SingleAlarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The Expression Playground on the shared [PlaygroundShell]: the expression on one side — with the
 * problems under it — and, on the other, *what it runs against* ([ContextPanel]: the payload and its
 * node for the frontend dialect, the environment and the live instance for the backend one) over *what
 * came out* ([PlaygroundResultPane]). Switching the dialect swaps the editor's language and the
 * context's controls; the layout never moves.
 *
 * Validation and the frontend's live evaluation run in [PlaygroundDiagnostics]; the backend's *Evaluate
 * Against App* (Ctrl+Enter) posts to a running app via the Flowable Inspect REST API. What the backend
 * card is pointed at is [PlaygroundTargets]. State persists per user in workspace.xml
 * ([FlowableExprPlaygroundState]); credentials come from the connection and the PasswordSafe.
 */
class FlowableExpressionPanel(val project: Project, stackedByDefault: Boolean = true) : JPanel(BorderLayout()), Disposable {

    private val LOG = logger<FlowableExpressionPanel>()

    data class ScopeItem(val key: String?, val label: String)

    private val state = FlowableExprPlaygroundState.getInstance(project)
    private val shell = PlaygroundShell(project, "expressions", stackedByDefault)
    internal val targets = PlaygroundTargets(project)

    var dialect: ExpressionDialect = state.dialect
        private set

    // -- editor + diagnostics ------------------------------------------------------------------------

    private val field: LanguageTextField = PlaygroundEditors.create(
        project, languageOf(state.dialect), state.expression(state.dialect), lineNumbers = false, minHeight = 56,
        onEditor = { diagnostics.editorAvailable(it) },
        onChange = {
            state.setExpression(dialect, expressionText())
            diagnostics.scheduleRevalidate()
        },
    )

    private val strip = PlaygroundProblemsStrip()
    private val wrapperHint = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(3, 2, 0, 2)
    }
    private val diagnostics: PlaygroundDiagnostics = PlaygroundDiagnostics(
        project, field, strip,
        object : PlaygroundDiagnostics.Host {
            override val dialect: ExpressionDialect get() = this@FlowableExpressionPanel.dialect
            override val payloadText: String get() = payloadField.text
            override val frontendScopeText: String get() = payloadScopeField.text
            override val showSubEvaluations: Boolean get() = state.showSubEvaluations
            override fun onFrontendResult(result: EvalResult?) = this@FlowableExpressionPanel.onFrontendResult(result)
            override fun onScopeStatus(status: PlaygroundDiagnostics.ScopeStatus?) = this@FlowableExpressionPanel.onScopeStatus(status)
        },
        this,
    )

    // -- frontend context ---------------------------------------------------------------------------

    private val payloadField: LanguageTextField = PlaygroundEditors.create(
        project, JsonLanguage.INSTANCE, state.payload, lineNumbers = false, minHeight = 80,
        onEditor = { editor ->
            // the editor materializes lazily (and is re-created) — its markup died with the old one
            payloadEditor = editor
            scopeHighlighter = null
            applyScopeHighlight()
        },
        onChange = {
            state.payload = payloadField.text
            if (dialect == ExpressionDialect.FRONTEND) {
                diagnostics.scheduleRevalidate()
                updateContextSummary()
            }
        },
    )

    /** The payload-scope path (`orders[2].items[0]`-style) — the node the frontend expression is evaluated *at*. */
    private val payloadScopeField = JBTextField(state.frontendScopePath).apply {
        emptyText.text = "whole payload"
        document.addDocumentListener(object : javax.swing.event.DocumentListener {
            private fun sync() {
                state.frontendScopePath = text
                if (dialect == ExpressionDialect.FRONTEND) {
                    diagnostics.scheduleRevalidate()
                    updateContextSummary()
                }
            }
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = sync()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = sync()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = sync()
        })
    }
    private var payloadEditor: EditorEx? = null
    private var scopeHighlighter: RangeHighlighter? = null
    private var lastScopeStatus: PlaygroundDiagnostics.ScopeStatus? = null

    // -- backend context ----------------------------------------------------------------------------

    /** Which app an evaluation runs against — a real combo, because it is a choice made while working. */
    private val connectionCombo = ComboBox<Target?>().apply {
        renderer = listCellRenderer<Target?> {
            val target = value
            if ((target as? Target.Env)?.connection?.requiresConfirmation == true) icon(AllIcons.Nodes.Padlock)
            text(target?.let { targets.label(it) } ?: targets.label(Target.None))
        }
        addActionListener { if (!populatingConnection) (selectedItem as? Target)?.let { targets.choose(it); updateConnectionStatus() } }
    }
    private var populatingConnection = false
    private var connectionAnchor: JComponent? = null
    /** Held so its enabled state can be recomputed: a lone [ActionButton] is updated when it is shown,
     *  not while the targets behind it come and go. */
    private var sessionTargetsButton: ActionButton? = null

    private val scopeTypeCombo = ComboBox(InspectClient.ScopeType.entries.toTypedArray()).apply {
        renderer = textListCellRenderer { it?.let(::scopeTypeLabel) }
        selectedItem = state.inspectScopeType
        addActionListener {
            (selectedItem as? InspectClient.ScopeType)?.let { state.inspectScopeType = it }
            updateContextSummary()
        }
    }
    private val scopeIdField = JBTextField(state.inspectScopeId, 16).apply {
        emptyText.text = "instance id"
        document.addDocumentListener(object : javax.swing.event.DocumentListener {
            private fun sync() { state.inspectScopeId = text; updateContextSummary() }
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = sync()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = sync()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = sync()
        })
    }
    // Named for what the engine actually wants: a *plan item instance* id — a task id pasted here comes
    // back as an internal server error.
    private val subScopeIdField = JBTextField(state.inspectSubScopeId, 16).apply {
        emptyText.text = "plan item instance id"
        document.addDocumentListener(object : javax.swing.event.DocumentListener {
            private fun sync() { state.inspectSubScopeId = text }
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = sync()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = sync()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = sync()
        })
    }

    // -- context + result ---------------------------------------------------------------------------

    private val contextCards = JPanel(CardLayout())
    private val context = ContextPanel("Against", state.contextExpanded) { state.contextExpanded = it }
    private val resultPane = PlaygroundResultPane(FRONTEND_EMPTY_HINT)

    var isEvaluating: Boolean = false
        private set
    val canEvaluateAgainstApp: Boolean
        get() = targets.baseUrl().isNotBlank() && scopeIdField.text.isNotBlank()

    // -- scope picker -------------------------------------------------------------------------------

    @Volatile private var scopeItems: List<ScopeItem> = listOf(ScopeItem(null, ALL_VARIABLES_LABEL))
    private var currentScope: ScopeItem = scopeItems.first()
    private var pendingScopeKey: String? = state.scopeKey
    private val scopeAlarm = SingleAlarm(::reloadScopeItems, 300, this)

    var showSubEvaluations: Boolean
        get() = state.showSubEvaluations
        set(value) {
            state.showSubEvaluations = value
            // Flip the inlays right now from the already-computed trace — no debounced re-evaluation,
            // whose async result could be dropped and leave the hints stuck hidden/shown.
            diagnostics.refreshSubEvaluations()
        }

    /** Editor over context and result, or beside them — the shell remembers the choice per tab. */
    var stacked: Boolean
        get() = shell.stacked
        set(value) { shell.stacked = value }

    init {
        val evaluate = EvaluateAgainstAppAction(this).also { it.registerCustomShortcutSet(CommonShortcuts.getCtrlEnter(), this) }
        shell.toolbar = ActionManager.getInstance()
            .createActionToolbar("FlowableExprPlayground", buildToolbarGroup(evaluate), true)
            .also { it.targetComponent = shell }
            .component

        shell.setEditorSide(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 6, 2, 6)
            add(field, BorderLayout.CENTER)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(wrapperHint)
                add(strip)
            }, BorderLayout.SOUTH)
        })
        contextCards.add(frontendContext(), ExpressionDialect.FRONTEND.name)
        contextCards.add(backendContext(), ExpressionDialect.BACKEND.name)
        context.setBody(contextCards)
        shell.setContext(context)
        shell.setResult(resultPane)
        add(shell, BorderLayout.CENTER)

        project.messageBus.connect(this).subscribe(AtlasEvents.TOPIC, object : AtlasEventsListener {
            override fun modelIndexUpdated() = scopeAlarm.cancelAndRequest()
            override fun settingsApplied() = adoptInspectSettings()
            override fun environmentsChanged() = adoptInspectSettings()
            override fun connectionSelectionChanged(kind: ConnectionKind) {
                // Only a Work pick speaks for this card, and only a *pick* — the catalog and settings
                // events above must not end a session target the user is working against.
                if (kind == ConnectionKind.WORK) targets.followEnvironmentPick = true
                adoptInspectSettings()
            }
            // A sub-project switch changes which connection this project points at, so the card has to
            // re-read too — without this it kept showing (and evaluating against) the previous scope's.
            override fun activeSubProjectChanged() = adoptInspectSettings()
        })

        reloadScopeItems()
        applyDialect()
        applyScope()
        updateConnectionStatus()
        diagnostics.scheduleRevalidate()
    }

    override fun dispose() {}

    /** For hosts that hand focus to the panel — the expression editor is the natural target. */
    val focusComponent: JComponent get() = this.field

    private fun expressionText(): String = this.field.text

    // ---- toolbar ------------------------------------------------------------------------------------

    private fun buildToolbarGroup(evaluate: AnAction): DefaultActionGroup {
        val group = DefaultActionGroup()
        group.add(DialectToggleAction(this, ExpressionDialect.BACKEND))
        group.add(DialectToggleAction(this, ExpressionDialect.FRONTEND))
        group.addSeparator()
        group.add(ScopeComboBoxAction(this))
        group.addSeparator()
        group.add(evaluate)
        group.add(ShowSubEvaluationsToggle(this))
        group.addSeparator()
        group.add(PlaygroundSettingsGroup(project, stackPanels = StackPanelsToggle({ stacked }, { stacked = it })))
        return group
    }

    // ---- dialect ------------------------------------------------------------------------------------

    /** Swap language + file type on the ONE editor field — no field re-creation; the field re-installs
     *  document listeners and re-runs the settings provider (which re-wires [diagnostics]) itself. */
    fun switchDialect(newDialect: ExpressionDialect) {
        if (newDialect == dialect) return
        // Park what is on screen under the dialect it belongs to, and bring back the other one's.
        // Carrying the text across looked like "your work is preserved" and was the opposite.
        state.setExpression(dialect, expressionText())
        dialect = newDialect
        state.dialect = newDialect
        val document = LanguageTextField.createDocument(
            state.expression(newDialect), languageOf(newDialect), project, LanguageTextField.SimpleDocumentCreator(),
        )
        field.setNewDocumentAndFileType(fileTypeOf(newDialect), document)
        applyDialect()
        applyScope()
        reloadScopeItems()
        diagnostics.scheduleRevalidate()
    }

    /** Everything that reads the dialect: the hint, the context card, the result's empty state. */
    private fun applyDialect() {
        wrapperHint.text = "Evaluated as ${dialect.open} <expression> ${dialect.close} — the ${dialect.open}${dialect.close} delimiters are optional here"
        (contextCards.layout as CardLayout).show(contextCards, dialect.name)
        context.setCaption(if (dialect == ExpressionDialect.FRONTEND) "Payload" else "Against")
        resultPane.setEmptyHint(if (dialect == ExpressionDialect.FRONTEND) FRONTEND_EMPTY_HINT else BACKEND_EMPTY_HINT)
        resultPane.showEmpty()
        updateContextSummary()
    }

    private fun fileTypeOf(dialect: ExpressionDialect): FlowableExprFileType =
        if (dialect == ExpressionDialect.BACKEND) FlowableBackendExprFileType else FlowableFrontendExprFileType

    /** Open the playground pre-filled (the Alt+Enter intention and the explorer's toolbar). */
    fun openWith(request: OpenRequest) {
        // Stored first, so switching *to* the dialect it belongs to brings this text and not the one
        // parked there earlier.
        state.setExpression(request.dialect, request.text)
        switchDialect(request.dialect)
        field.text = request.text
        pendingScopeKey = request.scopeKey
        scopeItems.firstOrNull { it.key == request.scopeKey }?.let { selectScope(it) }
        request.scopeType?.let { scopeTypeCombo.selectedItem = it }
        diagnostics.scheduleRevalidate()
        IdeFocusManager.getInstance(project).requestFocus(field, true)
    }

    // ---- the one summary line ------------------------------------------------------------------------

    /** `QA (project) · Case instance CAS-4711` or `payload, 14 lines, at orders[1].items[0]`. */
    private fun updateContextSummary() {
        if (dialect == ExpressionDialect.FRONTEND) {
            val lines = payloadField.text.lineSequence().count { it.isNotBlank() }
            val at = payloadScopeField.text.trim()
            context.setSummary(
                when {
                    lines == 0 -> "no payload — evaluates against {}"
                    at.isBlank() -> "payload, $lines line${if (lines == 1) "" else "s"}"
                    else -> "payload, $lines line${if (lines == 1) "" else "s"}, at $at"
                },
            )
        } else {
            val target = targets.current()
            val icon = if ((target as? Target.Env)?.connection?.requiresConfirmation == true) AllIcons.Nodes.Padlock else null
            val instance = scopeIdField.text.trim()
            val scope = (scopeTypeCombo.selectedItem as? InspectClient.ScopeType)?.let(::scopeTypeLabel) ?: ""
            context.setSummary(
                when {
                    target == Target.None -> targets.label(target)
                    instance.isBlank() -> "${targets.label(target)} · no instance yet"
                    else -> "${targets.label(target)} · $scope $instance"
                },
                icon,
            )
        }
    }

    // ---- frontend result ------------------------------------------------------------------------------

    private fun onFrontendResult(result: EvalResult?) {
        when (result) {
            null -> resultPane.showEmpty()
            is EvalResult.Ok -> resultPane.showOk(renderValue(result.value))
            is EvalResult.Err -> resultPane.showError(result.message)
            // valid, just not previewable statically — neutral, never reads as an invalid expression
            is EvalResult.Unavailable -> resultPane.showInfo(result.message)
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

    // ---- context cards --------------------------------------------------------------------------------

    private fun frontendContext(): JComponent = panel {
        row {
            cell(payloadField).align(Align.FILL)
        }.resizableRow()
        row("At node:") {
            cell(payloadScopeField).align(AlignX.FILL).resizableColumn()
                .comment("Evaluate as a component at this payload node — e.g. orders[2].items[0]; empty = whole payload. Binds \$item, \$index and \$itemParent like the form runtime.")
            button("From Cursor") { setScopeFromCaret() }
            button("Clear") { payloadScopeField.text = "" }
        }
    }

    private fun backendContext(): JComponent = panel {
        row("Environment:") {
            cell(connectionCombo).align(AlignX.FILL).resizableColumn()
                .applyToComponent { connectionAnchor = this }
            button("Paste Work URL…") { pasteWorkUrl() }
            // What can be done *to* a pasted target — name it, or forget it — rather than to the
            // environments. Disabled until there is one, so the row keeps its shape.
            actionButton(sessionTargetsAction()).applyToComponent { sessionTargetsButton = this }
            link("Manage Environments…") { manageEnvironments() }
        }
        row("Instance:") {
            cell(scopeTypeCombo)
            cell(scopeIdField).align(AlignX.FILL).resizableColumn()
        }
        row("Plan item:") { cell(subScopeIdField) }
    }

    /** Derive the scope path from the caret position in the payload JSON editor. */
    private fun setScopeFromCaret() {
        val editor = payloadEditor?.takeUnless { it.isDisposed }
        if (editor == null) {
            resultPane.showInfo("Place the caret on a node in the payload JSON first, then use “From Cursor”.")
            return
        }
        // a plain Swing button callback holds no write-intent lock, and committing a document needs one
        val path = WriteIntentReadAction.compute<PayloadScopePath?> {
            val documentManager = PsiDocumentManager.getInstance(project)
            documentManager.commitDocument(payloadField.document)
            documentManager.getPsiFile(payloadField.document)
                ?.let { PayloadJsonPaths.pathAt(it, editor.caretModel.offset) }
        }
        if (path == null) {
            resultPane.showInfo("Place the caret on a node in the payload JSON first, then use “From Cursor”.")
            return
        }
        payloadScopeField.text = path.format()   // the field's document listener re-evaluates
    }

    /** Reflect the last pass's scope check: error outline + tooltip on the field, node highlight in the editor. */
    private fun onScopeStatus(status: PlaygroundDiagnostics.ScopeStatus?) {
        lastScopeStatus = status
        val invalid = status as? PlaygroundDiagnostics.ScopeStatus.Invalid
        payloadScopeField.putClientProperty("JComponent.outline", if (invalid != null) "error" else null)
        payloadScopeField.toolTipText = invalid?.message
        applyScopeHighlight()
    }

    /** Tint the scoped payload node so the selection is visible where it matters — in the JSON itself. */
    private fun applyScopeHighlight() {
        val editor = payloadEditor?.takeUnless { it.isDisposed } ?: return
        scopeHighlighter?.let { if (it.isValid) editor.markupModel.removeHighlighter(it) }
        scopeHighlighter = null
        val valid = lastScopeStatus as? PlaygroundDiagnostics.ScopeStatus.Valid ?: return
        if (valid.path.isRoot) return
        // no commit here: PSI may lag the document for a moment, but every diagnostics pass re-fires
        // onScopeStatus, so a stale/missing highlight self-heals; PSI reads still need a read lock
        val range = runReadActionBlocking {
            PsiDocumentManager.getInstance(project).getPsiFile(payloadField.document)
                ?.let { PayloadJsonPaths.rangeOf(it, valid.path) }
        } ?: return
        val end = range.endOffset.coerceAtMost(editor.document.textLength)
        if (range.startOffset >= end) return
        // The scheme's own "identifier under caret" tint — the node reads as selected, in every theme.
        scopeHighlighter = editor.markupModel.addRangeHighlighter(
            EditorColors.IDENTIFIER_UNDER_CARET_ATTRIBUTES, range.startOffset, end,
            HighlighterLayer.SELECTION - 1, HighlighterTargetArea.EXACT_RANGE,
        )
    }

    // ---- scope ---------------------------------------------------------------------------------------------

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
     * Populates the scope items without ever building the model index on the EDT. The cached index fills
     * the list immediately; a cache miss loads on a pooled thread and applies the items when ready.
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

    private fun scopeItems(dialect: ExpressionDialect, keysOfType: (ModelType) -> List<ModelEntry>): List<ScopeItem> {
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

    // ---- backend targets -------------------------------------------------------------------------------------

    /** Refills the picker from [targets] and re-reads the summary line. */
    private fun updateConnectionStatus() {
        val choices = targets.choices()
        val target = targets.current()
        populatingConnection = true
        try {
            connectionCombo.model = DefaultComboBoxModel<Target?>().apply { choices.forEach { addElement(it) } }
            connectionCombo.selectedItem = target
        } finally {
            populatingConnection = false
        }
        connectionCombo.toolTipText = targets.tooltip(target)
        sessionTargetsButton?.update()
        updateContextSummary()
    }

    private fun manageEnvironments() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, EnvironmentsConfigurable::class.java)
    }

    /**
     * One gesture, one dialog: paste a Work link and it resolves the app, the scope and the instance —
     * checking the credentials for it when it is not one of your environments yet.
     */
    private fun pasteWorkUrl() {
        val dialog = PasteWorkUrlDialog(project)
        if (!dialog.showAndGet()) return
        applyPastedResult(dialog.result())
    }

    /** Separate from showing the dialog so the whole decision can be driven in a test without a window. */
    internal fun applyPastedResult(result: PasteWorkUrlDialog.Result) {
        val pasted = targets.applyPasted(result) ?: return
        result.parsed.scopeId?.let { scopeId ->
            result.parsed.scopeType?.let { scopeTypeCombo.selectedItem = it }
            scopeIdField.text = scopeId
            subScopeIdField.text = result.parsed.subScopeId ?: ""
        }
        updateConnectionStatus()
        // Said out loud, because the picker gaining no row looks like the paste having been ignored.
        // An app is the address a request goes to; `#/work` and `#/work2` are two screens inside one.
        val repeated = if (pasted.repeated) "\nAlready a target — what identifies an app is the address " +
            "before “#”, so another route in the same one is the same target." else ""
        resultPane.showInfo("Using ${pasted.where}" + (result.parsed.scopeId?.let { " · $it" } ?: "") + repeated)
    }

    /**
     * The pasted targets' own menu: name one, or forget it. Deliberately *not* four more links on a row
     * that already carries a picker, a button and a link.
     */
    private fun sessionTargetsAction(): AnAction =
        object : AnAction("Session Targets", "Save a pasted Work URL as an environment, or forget it", AllIcons.Actions.More), DumbAware {
            override fun actionPerformed(e: AnActionEvent) {
                JBPopupFactory.getInstance()
                    .createActionGroupPopup("Session Targets", sessionTargetActions(), e.dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
                    .showUnderneathOf(connectionAnchor ?: this@FlowableExpressionPanel)
            }

            override fun update(e: AnActionEvent) {
                val any = InspectSessionTargets.all().isNotEmpty()
                e.presentation.isEnabled = any
                // A disabled button that says why beats one that vanishes.
                e.presentation.description =
                    if (any) "Save a pasted Work URL as an environment, or forget it"
                    else "Paste a Work URL to get a target you can name or forget"
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

    private fun sessionTargetActions(): DefaultActionGroup {
        val group = DefaultActionGroup()
        val all = InspectSessionTargets.all()
        (targets.current() as? Target.Session)?.let { selected ->
            group.add(simpleAction("Save “${shortTarget(selected.baseUrl)}” as an Environment…") { saveSessionTarget(selected.baseUrl) })
            group.addSeparator()
        }
        all.forEach { url -> group.add(simpleAction("Forget “${shortTarget(url)}”") { forgetSessionTarget(url) }) }
        if (all.size > 1) group.add(simpleAction("Forget All (${all.size})") { all.forEach(::forgetSessionTarget) })
        if (all.isEmpty()) {
            group.add(simpleAction("Paste a Work URL to get a target you can name or forget") {}.apply { templatePresentation.isEnabled = false })
        }
        return group
    }

    private fun simpleAction(text: String, run: () -> Unit): AnAction =
        object : AnAction(text), DumbAware {
            override fun actionPerformed(e: AnActionEvent) = run()
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

    private fun shortTarget(baseUrl: String): String = com.flowable.atlas.environment.BaseUrls.withoutScheme(baseUrl)

    private fun forgetSessionTarget(baseUrl: String) {
        targets.forget(baseUrl)
        updateConnectionStatus()
        if (targets.current() == Target.None) resultPane.showInfo("Choose an environment, or paste a Work URL.")
    }

    private fun saveSessionTarget(baseUrl: String) {
        val dialog = SaveWorkTargetDialog(project, baseUrl)
        if (!dialog.showAndGet()) return
        saveSessionTargetAs(baseUrl, dialog.environmentName, dialog.isProtected)
    }

    private fun saveSessionTargetAs(baseUrl: String, name: String, protected: Boolean) {
        when (val saved = targets.saveAs(baseUrl, name, protected)) {
            is PlaygroundTargets.Saved.Ok -> { updateConnectionStatus(); resultPane.showInfo("Saved as ${saved.name}") }
            is PlaygroundTargets.Saved.AlreadyHasApp -> resultPane.showError("“${saved.name}” already has an app connection.")
        }
    }

    /** Re-reads the chosen connection after a catalog, selection or sub-project event. Any thread. */
    private fun adoptInspectSettings() {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            targets.adopt()
            updateConnectionStatus()
        }, ModalityState.any())
    }

    // ---- backend evaluation -------------------------------------------------------------------------------

    /**
     * Evaluates against the chosen environment — or the pasted URL — asking first when the environment is
     * protected. Protection follows the URL, not the pointer: a guard keyed on the selected connection
     * would be walked around by pasting PROD's link.
     */
    fun evaluateAgainstApp() {
        if (isEvaluating) return
        val baseUrl = targets.baseUrl().trim()
        if (baseUrl.isBlank()) {
            resultPane.showInfo("Choose an environment, or paste a Work URL.")
            return
        }
        val protecting = AtlasProtection.protecting(baseUrl, ConnectionKind.WORK, AtlasCatalog.connections(project))
        if (protecting != null) {
            AtlasProtection.confirmEvaluate(protecting, connectionAnchor ?: this) { runEvaluation(baseUrl) }
            return
        }
        runEvaluation(baseUrl)
    }

    private fun runEvaluation(baseUrl: String) {
        val connection: AtlasConnection? = targets.connection()
        val exprBody = expressionText().trim()
        val expr = if (exprBody.startsWith("\${") || exprBody.startsWith("#{")) exprBody else "\${$exprBody}"
        isEvaluating = true
        resultPane.showLoading("Evaluating against ${connection?.environmentName ?: shortTarget(baseUrl)}…")
        ApplicationManager.getApplication().executeOnPooledThread {
            // The keychain can block, so the credential read happens here rather than on the EDT.
            val auth = runCatching {
                AtlasCredentials.contextFor(baseUrl, connection?.authMode ?: AuthMode.BASIC, connection?.username.orEmpty())
            }
                .onFailure { LOG.warn("Could not read the credentials for $baseUrl from the PasswordSafe", it) }
                .getOrDefault(AuthContext(sessionHeaders = BrowserSessions.get(baseUrl).orEmpty()))
            val req = InspectClient.Request(
                baseUrl = baseUrl,
                expression = expr,
                scopeType = scopeTypeCombo.selectedItem as InspectClient.ScopeType,
                scopeId = scopeIdField.text.trim(),
                subScopeId = subScopeIdField.text.trim().ifBlank { null },
                auth = auth,
            )
            val outcome = InspectClient.evaluate(req)
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                isEvaluating = false
                when (outcome) {
                    is InspectClient.Outcome.Evaluated -> {
                        val r = outcome.response
                        if (r.valid) resultPane.showOk("${MiniJson.stringify(r.value)}   (${r.valueType ?: "?"})")
                        else resultPane.showError(r.exception ?: "Invalid expression")
                    }
                    is InspectClient.Outcome.Failed -> resultPane.showError(outcome.message)
                }
            }, ModalityState.any())
        }
    }

    // ---- for tests ----------------------------------------------------------------------------------------

    /** The base URL the backend card would evaluate against. */
    internal val inspectBaseUrlText: String get() = targets.baseUrl()

    /** What is in the editor right now. `this.` because bare `field` is the accessor's own backing field. */
    internal val expressionForTest: String get() = this.field.text

    internal fun setExpressionForTest(text: String) { this.field.text = text }

    /** The environment the card would evaluate against, or "" when none is selected. */
    internal val environmentNameForTest: String get() = targets.connection()?.environmentName.orEmpty()

    internal fun connectionItemCountForTest(): Int = connectionCombo.itemCount

    /** What the connection picker is showing — collected from the rendered component *tree*, since the
     *  `listCellRenderer` DSL paints into a nested SimpleColoredComponent. */
    internal fun connectionComboLabelForTest(): String {
        val component = connectionCombo.renderer.getListCellRendererComponent(
            javax.swing.JList(), connectionCombo.selectedItem as Target?, -1, false, false,
        )
        return renderedTexts(component).joinToString(" ").trim()
    }

    private fun renderedTexts(component: java.awt.Component): List<String> = when (component) {
        is SimpleColoredComponent -> listOf(component.getCharSequence(false).toString())
        is javax.swing.JLabel -> listOfNotNull(component.text)
        is java.awt.Container -> component.components.flatMap { renderedTexts(it) }
        else -> emptyList()
    }.filter { it.isNotBlank() }

    internal fun sessionTargetsForTest(): List<String> =
        (0 until connectionCombo.itemCount).mapNotNull { (connectionCombo.getItemAt(it) as? Target.Session)?.baseUrl }

    internal fun forgetSessionTargetForTest(baseUrl: String) = forgetSessionTarget(baseUrl)
    internal fun selectSessionTargetForTest(baseUrl: String) { targets.choose(Target.Session(baseUrl)); updateConnectionStatus() }
    internal fun saveSessionTargetForTest(baseUrl: String, name: String, protected: Boolean) = saveSessionTargetAs(baseUrl, name, protected)
    internal val contextSummaryForTest: String get() = context.summaryText
    internal val resultStateForTest: PlaygroundResultPane.ResultState get() = resultPane.state

    /** What a caller wants the playground opened with: the text, its dialect, and — when known — the model and the instance kind. */
    data class OpenRequest(
        val text: String,
        val dialect: ExpressionDialect,
        val scopeKey: String? = null,
        val scopeType: InspectClient.ScopeType? = null,
    )

    companion object {
        const val TOOL_WINDOW_ID = "Flowable Expressions"
        private const val ALL_VARIABLES_LABEL = "All variables"
        private const val FRONTEND_EMPTY_HINT = "Type an expression to evaluate"
        private const val BACKEND_EMPTY_HINT = "Choose an environment and a live instance id, then press Evaluate (Ctrl+Enter)"

        fun scopeTypeLabel(type: InspectClient.ScopeType): String = when (type) {
            InspectClient.ScopeType.BPMN -> "Process instance"
            InspectClient.ScopeType.CMMN -> "Case instance"
            InspectClient.ScopeType.TASK -> "Task"
        }

        /** Activate the playground tool window and pre-fill it — the intention's and the explorer's entry point. */
        fun open(project: Project, request: OpenRequest) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
            toolWindow.activate({
                // the tool window also hosts the Scripts tab — make sure the Expressions one shows
                val cm = toolWindow.contentManager
                val content = cm.contents.firstOrNull { it.component is FlowableExpressionPanel } ?: return@activate
                cm.setSelectedContent(content)
                (content.component as FlowableExpressionPanel).openWith(request)
            }, true)
        }
    }
}
