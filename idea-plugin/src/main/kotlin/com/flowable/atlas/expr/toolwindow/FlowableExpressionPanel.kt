package com.flowable.atlas.expr.toolwindow

import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.events.AtlasEventsListener
import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.expr.ExpressionScope
import com.flowable.atlas.expr.eval.EvalResult
import com.flowable.atlas.expr.eval.PayloadScopePath
import com.flowable.atlas.environment.AtlasCatalog
import com.flowable.atlas.environment.AtlasConnection
import com.flowable.atlas.environment.AtlasConnectionSelection
import com.flowable.atlas.environment.AtlasEnvironments
import com.flowable.atlas.environment.AtlasProtection
import com.flowable.atlas.environment.BaseUrls
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.environment.ConnectionLabels
import com.flowable.atlas.environment.auth.AtlasCredentials
import com.flowable.atlas.environment.auth.AuthContext
import com.flowable.atlas.environment.auth.AuthMode
import com.flowable.atlas.environment.auth.BrowserSessions
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
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.icons.AllIcons
import com.intellij.json.JsonLanguage
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.undo.UndoUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.LanguageTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.actionButton
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.SingleAlarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
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

    private val LOG = logger<FlowableExpressionPanel>()

    data class ScopeItem(val key: String?, val label: String)

    private val state = FlowableExprPlaygroundState.getInstance(project)
    private val settings = FlowableAtlasProjectSettings.getInstance(project)

    var dialect: ExpressionDialect = state.dialect
        private set

    // -- expression editor + diagnostics ------------------------------------------------------

    private val field: LanguageTextField = LanguageTextField(languageOf(state.dialect), project, state.expression(state.dialect), false).apply {
        border = JBUI.Borders.customLine(JBColor.border(), 1)
        // Floor the editor height so the splitter (once it honors component minimums) keeps it usable
        // even when the tool window is docked short at the bottom of the screen.
        minimumSize = Dimension(JBUI.scale(120), JBUI.scale(56))
        addSettingsProvider { editor ->
            // A LanguageTextField's document isn't file-backed, so the platform's global $Undo action
            // doesn't track it out of the box (no Ctrl+Z). Enable undo explicitly — this settings
            // provider re-runs after a dialect switch swaps the document, so the new document gets it too.
            UndoUtil.enableUndoFor(editor.document)
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
                state.setExpression(dialect, text)
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
            override val frontendScopeText: String get() = payloadScopeField.text
            override val showSubEvaluations: Boolean get() = state.showSubEvaluations
            override fun onFrontendResult(result: EvalResult?) = this@FlowableExpressionPanel.onFrontendResult(result)
            override fun onScopeStatus(status: PlaygroundDiagnostics.ScopeStatus?) = this@FlowableExpressionPanel.onScopeStatus(status)
        },
        this,
    )

    // -- frontend card -------------------------------------------------------------------------

    private val payloadField = LanguageTextField(JsonLanguage.INSTANCE, project, state.payload, false).apply {
        border = JBUI.Borders.customLine(JBColor.border(), 1)
        // floor the height so the payload/result splitter can shrink it to give the result room, never to nothing
        minimumSize = Dimension(JBUI.scale(120), JBUI.scale(80))
        addSettingsProvider { editor ->
            UndoUtil.enableUndoFor(editor.document)   // Ctrl+Z in the payload field too (see the expression field)
            editor.setVerticalScrollbarVisible(true)
            editor.setBorder(JBUI.Borders.empty(4))
            editor.settings.apply {
                isLineNumbersShown = false
                isFoldingOutlineShown = false
                isLineMarkerAreaShown = false
                isCaretRowShown = false
            }
            // the editor materializes lazily (and is re-created) — its markup died with the old one
            payloadEditor = editor
            scopeHighlighter = null
            applyScopeHighlight()
        }
        addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                state.payload = text
                if (dialect == ExpressionDialect.FRONTEND) diagnostics.scheduleRevalidate()
            }
        })
    }

    /** The payload-scope path (`orders[2].items[0]`-style) — the node the frontend expression is evaluated *at*. */
    private val payloadScopeField = JBTextField(state.frontendScopePath).apply {
        emptyText.text = "(root)"
        document.addDocumentListener(object : javax.swing.event.DocumentListener {
            private fun sync() {
                state.frontendScopePath = text
                if (dialect == ExpressionDialect.FRONTEND) diagnostics.scheduleRevalidate()
            }
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = sync()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = sync()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = sync()
        })
    }
    private var payloadEditor: EditorEx? = null
    private var scopeHighlighter: RangeHighlighter? = null
    private var lastScopeStatus: PlaygroundDiagnostics.ScopeStatus? = null
    private val frontendResultPane = PlaygroundResultPane("Type an expression to evaluate")

    // -- backend (Inspect) card ------------------------------------------------------------------

    /**
     * What an evaluation can run against: one of the defined environments, or one of the URLs pasted
     * into this IDE session ([InspectSessionTargets]) — which are not environments and never become
     * one by accident.
     *
     * A type rather than "an [AtlasConnection], or null meaning the ad-hoc one". That encoding held
     * exactly one ad-hoc target because `null` is not a value you can have two of, and the second
     * pasted link therefore evicted the first.
     */
    private sealed interface Target {

        data class Env(val connection: AtlasConnection) : Target

        /** A pasted URL, alive for this IDE session and written down nowhere. */
        data class Session(val baseUrl: String) : Target

        /** Nothing to evaluate against — offered only while it is the actual state. */
        data object None : Target
    }

    /**
     * Which app an evaluation runs against — a real combo, not a status line with a link. This is a
     * choice the user makes while working, over and over, and it should look like one at a glance.
     */
    private val connectionCombo = ComboBox<Target?>().apply {
        renderer = SimpleListCellRenderer.create<Target?> { label, value, _ ->
            label.text = when (value) {
                is Target.Env -> ConnectionLabels.pickerItem(value.connection)
                // Host, port and path — the scheme is the only part that never tells two of these
                // apart, and two apps on one machine differ by exactly the parts that stay.
                // "(this session)" is what says it is not one of the environments.
                is Target.Session -> "${BaseUrls.withoutScheme(value.baseUrl)} (this session)"
                else -> "no environment yet"
            }
            label.icon =
                if ((value as? Target.Env)?.connection?.requiresConfirmation == true) AllIcons.Nodes.Padlock
                else null
        }
        addActionListener { if (!populatingConnection) chooseTarget(selectedItem) }
    }
    private var populatingConnection = false

    /**
     * The session target this card is pointed at, or null when it follows the project's chosen
     * environment. Panel state, not catalog state: the targets themselves are IDE-wide
     * ([InspectSessionTargets]) so a second playground can see them, but *which one this card uses* is
     * the same kind of local choice the environment picker makes.
     */
    private var selectedSessionUrl: String? = null

    /**
     * Set by the (any-thread) event listener when a Work environment was picked somewhere else, and
     * consumed on the EDT: that pick is a decision about this card too, so it wins over a session
     * target selected here. Nothing outside can point *at* a session target, so there is no reverse.
     */
    @Volatile
    private var followEnvironmentPick = false

    private var connectionAnchor: javax.swing.JComponent? = null

    /** Held so its enabled state can be recomputed: a lone [ActionButton] is updated when it is shown,
     *  not while the targets behind it come and go. */
    private var sessionTargetsButton: ActionButton? = null
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
    // Named for what the engine actually wants: a *plan item instance* id. Calling it "optional" was
    // true and useless, and a task id pasted here comes back as an internal server error.
    private val subScopeIdField = JBTextField(16).apply { emptyText.text = "plan item instance id" }
    private val backendResultPane = PlaygroundResultPane("Pick an environment, give a live instance id, and evaluate")

    var isEvaluating: Boolean = false
        private set
    val canEvaluateAgainstApp: Boolean
        get() = currentBaseUrl().isNotBlank() && scopeIdField.text.isNotBlank()

    // -- scope picker -----------------------------------------------------------------------------

    @Volatile private var scopeItems: List<ScopeItem> = listOf(ScopeItem(null, ALL_VARIABLES_LABEL))
    private var currentScope: ScopeItem = scopeItems.first()
    private var pendingScopeKey: String? = state.scopeKey
    private val scopeAlarm = SingleAlarm(::reloadScopeItems, 300, this)

    /** Debounces parsing of [appUrlField] so a paste fills the form once, not per keystroke. */

    private val cards = JPanel(CardLayout())

    var showSubEvaluations: Boolean
        get() = state.showSubEvaluations
        set(value) {
            state.showSubEvaluations = value
            // Flip the inlays right now from the already-computed trace — no debounced re-evaluation,
            // whose async result could be dropped and leave the hints stuck hidden/shown.
            diagnostics.refreshSubEvaluations()
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

        val splitter = JBSplitter(true, "flowable.atlas.expr.playground.splitter", 0.45f).apply {
            firstComponent = editorSection
            secondComponent = cards
            // a grabbable divider (the old OnePixelSplitter's 1px line was near-impossible to grab) so the
            // expression editor and the payload/scope card can be resized against each other — width when
            // docked landscape (side-by-side), height when portrait (stacked)
            dividerWidth = JBUI.scale(6)
            setHonorComponentsMinimumSize(true)
        }
        // Adapt to the tool window's shape: stack the editor over the payload/result when the panel is
        // portrait (docked left/right, the usual case), but lay them side-by-side when it is clearly
        // landscape (docked short at the bottom of the screen) so the editor keeps its full height
        // instead of being squeezed into a sliver.
        splitter.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val w = splitter.width
                val h = splitter.height
                if (w == 0 || h == 0) return
                val wantVertical = w <= h * 1.3
                if (splitter.isVertical != wantVertical) splitter.orientation = wantVertical
            }
        })
        setContent(splitter)

        project.messageBus.connect(this).subscribe(AtlasEvents.TOPIC, object : AtlasEventsListener {
            override fun modelIndexUpdated() = scopeAlarm.cancelAndRequest()
            override fun settingsApplied() = adoptInspectSettings()
            override fun environmentsChanged() = adoptInspectSettings()
            override fun connectionSelectionChanged(kind: ConnectionKind) {
                // Only a Work pick speaks for this card, and only a *pick* — the catalog and settings
                // events above must not end a session target the user is working against.
                if (kind == ConnectionKind.WORK) followEnvironmentPick = true
                adoptInspectSettings()
            }
            // A sub-project switch changes which connection this project points at, so the card has to
            // re-read too — without this it kept showing (and evaluating against) the previous scope's.
            override fun activeSubProjectChanged() = adoptInspectSettings()
        })

        reloadScopeItems()
        updateWrapperHint()
        applyScope()
        showCard()
        updateConnectionStatus()
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
        // Park what is on screen under the dialect it belongs to, and bring back the other one's.
        // Carrying the text across looked like "your work is preserved" and was the opposite: a
        // backend expression landed in the frontend editor and overwrote what had been there.
        state.setExpression(dialect, field.text)
        dialect = newDialect
        state.dialect = newDialect
        val document = LanguageTextField.createDocument(
            state.expression(newDialect), languageOf(newDialect), project, LanguageTextField.SimpleDocumentCreator(),
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
        // Stored first, so switching *to* the dialect it belongs to brings this text and not the one
        // parked there earlier.
        state.setExpression(dialect, text)
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
        row("Scope:") {
            cell(payloadScopeField).align(AlignX.FILL).resizableColumn()
                .comment("Evaluate as a component at this payload node — e.g. orders[2].items[0]; empty = whole payload. Binds \$item, \$index and \$itemParent like the form runtime.")
        }
        row("") {
            button("From Cursor") { setScopeFromCaret() }
            button("Clear") { payloadScopeField.text = "" }
        }
        row {
            // payload editor over the result, split by a grabbable divider so the result box can be dragged
            // taller for large payloads (it still scrolls); resizableRow lets the pair fill the card height
            cell(
                JBSplitter(true, "flowable.atlas.expr.playground.frontend.result.splitter", 0.65f).apply {
                    firstComponent = payloadField
                    secondComponent = frontendResultPane
                    dividerWidth = JBUI.scale(6)
                    setHonorComponentsMinimumSize(true)
                },
            ).align(Align.FILL)
        }.resizableRow()
    }

    /** Derive the scope path from the caret position in the payload JSON editor. */
    private fun setScopeFromCaret() {
        val editor = payloadEditor?.takeUnless { it.isDisposed }
        if (editor == null) {
            frontendResultPane.showInfo("Place the caret on a node in the payload JSON first, then use “From Cursor”.")
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
            frontendResultPane.showInfo("Place the caret on a node in the payload JSON first, then use “From Cursor”.")
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
        val range = runReadAction {
            PsiDocumentManager.getInstance(project).getPsiFile(payloadField.document)
                ?.let { PayloadJsonPaths.rangeOf(it, valid.path) }
        } ?: return
        val end = range.endOffset.coerceAtMost(editor.document.textLength)
        if (range.startOffset >= end) return
        val attrs = TextAttributes().apply { backgroundColor = SCOPE_BG }
        scopeHighlighter = editor.markupModel.addRangeHighlighter(
            range.startOffset, end, HighlighterLayer.SELECTION - 1, attrs, HighlighterTargetArea.EXACT_RANGE,
        )
    }

    private fun backendCard(): JComponent = panel {
        row("Environment:") {
            cell(connectionCombo).align(AlignX.FILL).resizableColumn()
                .applyToComponent { connectionAnchor = this }
            button("Paste Work URL…") { pasteWorkUrl() }
            // What can be done *to* a pasted target — name it, or forget it — rather than to the
            // environments. Disabled until there is one, so the row does not grow and shrink under the
            // cursor as targets come and go, and so it can say why it is doing nothing.
            actionButton(sessionTargetsAction()).applyToComponent { sessionTargetsButton = this }
            link("Manage…") { manageEnvironments() }
        }
        row("Scope:") {
            cell(scopeTypeCombo)
            label("Instance id:")
            cell(scopeIdField).align(AlignX.FILL).resizableColumn()
        }
        row("Sub-scope id:") { cell(subScopeIdField) }
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

    /** The environment an evaluation runs against, or null when a session target is the one in use. */
    private fun currentConnection(): AtlasConnection? = (currentTarget() as? Target.Env)?.connection

    private fun currentBaseUrl(): String = when (val target = currentTarget()) {
        is Target.Env -> target.connection.baseUrl
        is Target.Session -> target.baseUrl
        Target.None -> ""
    }

    /**
     * What the card is pointed at. A session target the user picked wins, because picking it is the
     * most recent thing they said; otherwise the project's chosen environment answers, exactly as it
     * does everywhere else in the plugin.
     *
     * A target forgotten from another playground simply stops being the answer — this reads through to
     * [InspectSessionTargets] rather than trusting a field that could outlive the list.
     */
    private fun currentTarget(): Target {
        selectedSessionUrl?.takeIf { InspectSessionTargets.contains(it) }?.let { return Target.Session(it) }
        val connection = AtlasConnectionSelection.selected(project, ConnectionKind.WORK) ?: return Target.None
        return Target.Env(connection)
    }

    /**
     * Refills the picker: every environment, then every URL pasted into this session.
     *
     * "No environment yet" is added only while it *is* the state — a combo that always offers "nothing"
     * invites choosing it, and there is nothing on the other side of that choice.
     */
    private fun updateConnectionStatus() {
        // Drop a selection whose target another playground (or this one) has forgotten, so the field
        // cannot quietly resurrect it the next time the list changes.
        if (selectedSessionUrl?.let { !InspectSessionTargets.contains(it) } == true) selectedSessionUrl = null
        val target = currentTarget()
        val environments = AtlasCatalog.connections(project, ConnectionKind.WORK)
        val sessions = InspectSessionTargets.all()
        populatingConnection = true
        try {
            connectionCombo.model = DefaultComboBoxModel<Target?>().apply {
                if (target == Target.None) addElement(Target.None)
                environments.forEach { addElement(Target.Env(it)) }
                sessions.forEach { addElement(Target.Session(it)) }
            }
            connectionCombo.selectedItem = target
        } finally {
            populatingConnection = false
        }
        connectionCombo.toolTipText = when (target) {
            is Target.Env -> ConnectionLabels.tooltip(
                ConnectionKind.WORK, AtlasConnectionSelection.Resolution.Selected(target.connection, true),
            )
            // The row shows the host; the tooltip is where the rest of the URL — the context path that
            // decides which app it is — has room.
            is Target.Session -> "<html>${target.baseUrl}<br>Kept for this IDE session only.</html>"
            Target.None -> null
        }
        sessionTargetsButton?.update()
    }

    private fun chooseTarget(item: Any?) {
        when (val target = item as? Target) {
            // Picking an environment *is* the switch — it never sends the user to Settings to first
            // create what the entry already promised. It also ends the session target: two answers to
            // "which app?" is the state this card is not allowed to be in.
            is Target.Env -> {
                selectedSessionUrl = null
                AtlasConnectionSelection.select(project, ConnectionKind.WORK, target.connection.id)
            }
            is Target.Session -> selectedSessionUrl = target.baseUrl
            else -> Unit
        }
        updateConnectionStatus()
    }

    private fun manageEnvironments() {
        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, com.flowable.atlas.settings.EnvironmentsConfigurable::class.java)
    }

    /**
     * One gesture, one dialog: paste a Work link and it resolves the app, the scope and the instance —
     * checking the credentials for it when it is not one of your environments yet. This used to be a
     * field, a sentence explaining the field, a *Save as environment…* link and an "unsaved" state in
     * the picker, all sitting in the card whether or not anyone was pasting anything. Naming a target
     * still exists — in [sessionTargetActions], reachable from the target it is about, once the user
     * has decided it is one they keep coming back to.
     */
    private fun pasteWorkUrl() {
        val dialog = PasteWorkUrlDialog(project)
        if (!dialog.showAndGet()) return
        applyPastedResult(dialog.result())
    }

    /**
     * Points the card at what the paste dialog resolved. Separate from showing the dialog so the whole
     * decision — known app, unknown app, credentials, scope — can be driven in a test without a window.
     */
    internal fun applyPastedResult(result: PasteWorkUrlDialog.Result) {
        if (result.baseUrl.isBlank()) return
        var repeatedTarget = false
        val known = result.connectionId
        if (known != null) {
            selectedSessionUrl = null
            AtlasConnectionSelection.select(project, ConnectionKind.WORK, known)
        } else {
            // Nothing is created and nothing is written: the target lives in this IDE session, and its
            // credentials go to the in-memory session store as a basic Authorization header — the same
            // header a browser sign-in or a pasted cURL would have put there.
            //
            // It is *added* to the session targets, not swapped in: a second pasted link is a second
            // app to look at, and the first one stays a click away in the picker. The project's chosen
            // environment is left alone for the same reason — this is where the card is pointed now,
            // not a decision about what the project uses.
            repeatedTarget = InspectSessionTargets.contains(result.baseUrl)
            selectedSessionUrl = InspectSessionTargets.add(result.baseUrl)
            if (result.username.isNotBlank()) {
                val encoded = java.util.Base64.getEncoder()
                    .encodeToString("${result.username}:${result.password}".toByteArray())
                BrowserSessions.set(result.baseUrl, mapOf("Authorization" to "Basic $encoded"))
            }
        }
        result.parsed.scopeId?.let { scopeId ->
            result.parsed.scopeType?.let { scopeTypeCombo.selectedItem = it }
            scopeIdField.text = scopeId
            subScopeIdField.text = result.parsed.subScopeId ?: ""
        }
        updateConnectionStatus()
        val where = currentConnection()?.environmentName ?: BaseUrls.withoutScheme(result.baseUrl)
        // Said out loud, because the picker gaining no row looks like the paste having been ignored.
        // An app is the address a request goes to; `#/work` and `#/work2` are two screens inside one.
        val repeated = if (repeatedTarget) "\nAlready a target — what identifies an app is the address " +
            "before “#”, so another route in the same one is the same target." else ""
        backendResultPane.showInfo("Using $where" + (result.parsed.scopeId?.let { " · $it" } ?: "") + repeated)
    }

    // ---- session targets --------------------------------------------------------------------------

    /**
     * The pasted targets' own menu: name one, or forget it. Deliberately *not* four more links on a row
     * that already carries a picker, a button and a link — these are things you do to a target once,
     * not while you work, and the row is what you read every time.
     */
    private fun sessionTargetsAction(): AnAction =
        object : AnAction(
            "Session Targets",
            "Save a pasted Work URL as an environment, or forget it",
            AllIcons.Actions.More,
        ), DumbAware {
            override fun actionPerformed(e: AnActionEvent) {
                JBPopupFactory.getInstance()
                    .createActionGroupPopup(
                        "Session Targets", sessionTargetActions(), e.dataContext,
                        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true,
                    )
                    .showUnderneathOf(connectionAnchor ?: this@FlowableExpressionPanel)
            }

            override fun update(e: AnActionEvent) {
                val any = InspectSessionTargets.all().isNotEmpty()
                e.presentation.isEnabled = any
                // A disabled button that says why beats one that vanishes: the row keeps its shape, and
                // "paste a link" is the answer to "why can I not click this?".
                e.presentation.description =
                    if (any) "Save a pasted Work URL as an environment, or forget it"
                    else "Paste a Work URL to get a target you can name or forget"
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

    /**
     * One *Forget* per target rather than only for the selected one: the picker is the only place these
     * exist, so removing the one you are not looking at should not first mean going to look at it.
     */
    private fun sessionTargetActions(): DefaultActionGroup {
        val group = DefaultActionGroup()
        val targets = InspectSessionTargets.all()
        (currentTarget() as? Target.Session)?.let { selected ->
            group.add(simpleAction("Save “${shortTarget(selected.baseUrl)}” as an Environment…") {
                saveSessionTarget(selected.baseUrl)
            })
            group.addSeparator()
        }
        targets.forEach { url ->
            group.add(simpleAction("Forget “${shortTarget(url)}”") { forgetSessionTarget(url) })
        }
        if (targets.size > 1) {
            group.add(simpleAction("Forget All (${targets.size})") { targets.forEach(::forgetSessionTarget) })
        }
        // Belt and braces for the case where the button's enabled state has not caught up: a popup that
        // opens empty says nothing, and this says what to do.
        if (targets.isEmpty()) {
            group.add(
                simpleAction("Paste a Work URL to get a target you can name or forget") {}
                    .apply { templatePresentation.isEnabled = false },
            )
        }
        return group
    }

    private fun simpleAction(text: String, run: () -> Unit): AnAction =
        object : AnAction(text), DumbAware {
            override fun actionPerformed(e: AnActionEvent) = run()
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

    /** Named the way the picker names it, so a menu item is recognisably about the row above it. */
    private fun shortTarget(baseUrl: String): String = BaseUrls.withoutScheme(baseUrl)

    /**
     * Drops a target *and* whatever was captured for it. Forgetting is the whole point of these
     * entries, so it has to be complete: a cookie left behind for a URL that is no longer in any list
     * is a credential nobody can see and nobody asked to keep.
     */
    private fun forgetSessionTarget(baseUrl: String) {
        InspectSessionTargets.remove(baseUrl)
        BrowserSessions.clear(baseUrl)
        updateConnectionStatus()
        if (currentTarget() == Target.None) {
            backendResultPane.showInfo("Choose an environment, or paste a Work URL above.")
        }
    }

    /**
     * Turns the selected session target into a real environment — the one thing a one-off cannot do for
     * itself, because an environment needs a name and nothing here knows it.
     *
     * The captured basic-auth pair goes with it, into the PasswordSafe: the user typed it in the paste
     * dialog, and asking again for the same password because the target was renamed would be a toll for
     * nothing. A captured *session* (an SSO cookie) stays in [InspectSession] untouched — the catalog
     * has nowhere to put it, and dropping it would break the next evaluation against an app that can
     * only be reached that way.
     */
    private fun saveSessionTarget(baseUrl: String) {
        val dialog = SaveWorkTargetDialog(project, baseUrl)
        if (!dialog.showAndGet()) return
        saveSessionTargetAs(baseUrl, dialog.environmentName, dialog.isProtected)
    }

    /** The half of [saveSessionTarget] that has no window in it, so the decision can be driven in a test. */
    private fun saveSessionTargetAs(baseUrl: String, name: String, protected: Boolean) {
        val catalog = AtlasEnvironments.getInstance()
        // The developer's own list, not the merged one: this creates, and only that list can be
        // written to. A name matching a *shared* environment is fine and means "my own QA" — which
        // then shadows the project's, exactly as picking that name anywhere else would.
        val environmentId = catalog.environments().firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
            ?: catalog.addEnvironment(name, protected)
        val captured = BrowserSessions.basicAuth(baseUrl)
        val connectionId =
            catalog.addConnection(environmentId, ConnectionKind.WORK, baseUrl, captured?.first.orEmpty())
        if (connectionId == null) {
            // The dialog validates this, so getting here means the catalog changed under the dialog.
            backendResultPane.showError("“$name” already has an app connection.")
            return
        }
        InspectSessionTargets.remove(baseUrl)
        selectedSessionUrl = null
        AtlasConnectionSelection.select(project, ConnectionKind.WORK, connectionId)
        captured?.let { (username, password) ->
            // The PasswordSafe is the OS keychain, which can block or prompt.
            ApplicationManager.getApplication().executeOnPooledThread {
                AtlasCredentials.save(baseUrl, username, password)
            }
        }
        updateConnectionStatus()
        backendResultPane.showInfo("Saved as $name")
    }

    /**
     * Re-reads the chosen connection. Subscribed to the catalog and selection events *and* to a
     * sub-project switch, so the card can never keep showing a connection the project no longer uses —
     * which is the class of bug this whole rework exists to remove.
     */
    private fun adoptInspectSettings() {
        // The event contract allows any thread, and this touches Swing.
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            if (followEnvironmentPick) {
                followEnvironmentPick = false
                selectedSessionUrl = null
            }
            updateConnectionStatus()
        }, ModalityState.any())
    }


    /** For tests: the base URL the backend card would evaluate against. */
    internal val inspectBaseUrlText: String get() = currentBaseUrl()

    /** For tests: what is in the editor right now. `this.` because bare `field` is the accessor's
     *  own backing field — the same reason [focusComponent] spells it out. */
    internal val expressionForTest: String get() = this.field.text

    internal fun setExpressionForTest(text: String) {
        this.field.text = text
    }

    /** For tests: the environment the card would evaluate against, or "" when none is selected. */
    internal val environmentNameForTest: String get() = currentConnection()?.environmentName.orEmpty()

    /** For tests: how many choices the connection picker offers. */
    internal fun connectionItemCountForTest(): Int = connectionCombo.itemCount

    /** For tests: what the connection picker is showing right now. */
    internal fun connectionComboLabelForTest(): String {
        val renderer = connectionCombo.renderer
        val component = renderer.getListCellRendererComponent(
            javax.swing.JList(), connectionCombo.selectedItem as Target?, -1, false, false,
        )
        return (component as? javax.swing.JLabel)?.text.orEmpty()
    }

    /** For tests: the session targets the picker is offering, by base URL. */
    internal fun sessionTargetsForTest(): List<String> =
        (0 until connectionCombo.itemCount).mapNotNull { (connectionCombo.getItemAt(it) as? Target.Session)?.baseUrl }

    /** Forget one target the way the picker's menu does — for tests, which have no popup. */
    internal fun forgetSessionTargetForTest(baseUrl: String) = forgetSessionTarget(baseUrl)

    /** Pick a session target the way the picker does — for tests, which have no combo to click. */
    internal fun selectSessionTargetForTest(baseUrl: String) = chooseTarget(Target.Session(baseUrl))

    /** Save a session target the way the menu does, minus the dialog — for tests. */
    internal fun saveSessionTargetForTest(baseUrl: String, name: String, protected: Boolean) =
        saveSessionTargetAs(baseUrl, name, protected)

    /**
     * Evaluates against the chosen environment — or the ad-hoc URL — asking first when the environment
     * is protected.
     *
     * Two things it deliberately no longer does. It does not write the base URL and username into the
     * project settings: those are IDE-wide now, and an evaluation must not mutate an environment shared
     * with every other project — a pasted QA link used to end up as the team's committed configuration
     * that way. And it does not read credentials off the card, because the card no longer has any: they
     * come from the connection and the PasswordSafe.
     */
    fun evaluateAgainstApp() {
        if (isEvaluating) return
        val baseUrl = currentBaseUrl().trim()
        if (baseUrl.isBlank()) {
            backendResultPane.showInfo("Choose an environment above, or paste a Work URL.")
            return
        }
        // Protection follows the URL, not the pointer: the card can hold a typed URL, so a guard keyed
        // on the selected connection would be walked around by pasting PROD's link.
        val protecting = AtlasProtection.protecting(
            baseUrl, ConnectionKind.WORK, AtlasCatalog.connections(project),
        )
        if (protecting != null) {
            AtlasProtection.confirmEvaluate(protecting, connectionAnchor ?: this) { runEvaluation(baseUrl) }
            return
        }
        runEvaluation(baseUrl)
    }

    private fun runEvaluation(baseUrl: String) {
        val connection = currentConnection()
        val exprBody = field.text.trim()
        val expr = if (exprBody.startsWith("\${") || exprBody.startsWith("#{")) exprBody else "\${$exprBody}"
        isEvaluating = true
        backendResultPane.showLoading()
        ApplicationManager.getApplication().executeOnPooledThread {
            // The keychain can block, so the credential read happens here rather than on the EDT. One
            // call assembles the stored secret for the connection's mode *and* any captured browser
            // session — the same one a Design pull makes, so an app behind an identity provider is
            // reached the same way whichever half of Atlas is asking.
            val auth = runCatching {
                AtlasCredentials.contextFor(
                    baseUrl,
                    connection?.authMode ?: AuthMode.BASIC,
                    connection?.username.orEmpty(),
                )
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

        /** Scope-node tint in the payload editor (light, dark) — subtle, below the selection layer. */
        private val SCOPE_BG = JBColor(0xDDEBF9, 0x2D3B4E)

        /** Activate the playground tool window and pre-fill it — the intention's entry point. */
        fun open(project: Project, text: String, dialect: ExpressionDialect, scopeKey: String? = null) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
            toolWindow.activate({
                // the tool window also hosts the Scripts tab — make sure the Expressions one shows
                val cm = toolWindow.contentManager
                val content = cm.contents.firstOrNull { it.component is FlowableExpressionPanel } ?: return@activate
                cm.setSelectedContent(content)
                (content.component as FlowableExpressionPanel).openWithExpression(text, dialect, scopeKey)
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
    private val text = JBTextArea(6, 40).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        isOpaque = false
        // keep a few lines visible so the splitter's honored minimum can't collapse the result away
        minimumSize = Dimension(JBUI.scale(80), JBUI.scale(72))
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
