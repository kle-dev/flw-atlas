package com.flowable.atlas.expr.toolwindow

import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.events.AtlasEventsListener
import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.expr.ExpressionScope
import com.flowable.atlas.expr.eval.EvalResult
import com.flowable.atlas.expr.eval.PayloadScopePath
import com.flowable.atlas.environment.AtlasConnection
import com.flowable.atlas.environment.AtlasConnectionSelection
import com.flowable.atlas.environment.AtlasEnvironments
import com.flowable.atlas.environment.AtlasProtection
import com.flowable.atlas.environment.BaseUrls
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.environment.ConnectionLabels
import com.flowable.atlas.environment.EnvironmentNames
import com.flowable.atlas.expr.inspect.InspectClient
import com.flowable.atlas.expr.inspect.InspectCredentials
import com.flowable.atlas.expr.inspect.InspectSession
import com.flowable.atlas.expr.inspect.PasteWorkUrlDialog
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
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
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
     * Which app an evaluation runs against — a real combo, not a status line with a link. This is a
     * choice the user makes while working, over and over, and it should look like one at a glance.
     * `null` is the ad-hoc entry: a base URL pasted from a Work link that matches no environment.
     */
    private val connectionCombo = ComboBox<AtlasConnection?>().apply {
        renderer = SimpleListCellRenderer.create<AtlasConnection?> { label, value, _ ->
            label.text = value?.environmentName?.ifBlank { "unnamed" }
                ?: oneOffBaseUrl.takeIf { it.isNotBlank() }
                    ?.let { "${BaseUrls.host(it).ifBlank { it }} (this session)" }
                ?: "no environment yet"
            label.icon = if (value?.requiresConfirmation == true) AllIcons.Nodes.Padlock else null
        }
        addActionListener { if (!populatingConnection) chooseConnection(selectedItem) }
    }
    private var populatingConnection = false

    /**
     * A base URL from *Paste Work URL…* that is not one of the defined environments — kept **in memory
     * only**, for as long as this tool window lives. Deliberately not persisted and deliberately not
     * turned into an environment: a link from a colleague or a one-off look at a stage you do not work
     * against should leave nothing behind. Its credentials live in [InspectSession], the same
     * IDE-session store a browser sign-in uses, so nothing typed for it reaches the disk either.
     */
    private var oneOffBaseUrl: String = ""
    private var connectionAnchor: javax.swing.JComponent? = null
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
            override fun connectionSelectionChanged(kind: ConnectionKind) = adoptInspectSettings()
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

    /**
     * The app an evaluation runs against: the chosen environment, else the one-off URL from a paste.
     *
     * The `explicit` check is the point. With a single Work environment defined, the selection resolves
     * to it even when nothing was ever picked — a convenience that means the one-environment user never
     * has to choose anything. That fallback must not outrank a URL the user has just pasted, or the
     * one-off silently evaluates against the wrong app and the picker shows an environment the user did
     * not choose. An *explicit* pick still wins: that is the user changing their mind.
     */
    private fun currentConnection(): AtlasConnection? {
        val selected = AtlasConnectionSelection.resolution(project, ConnectionKind.WORK)
            as? AtlasConnectionSelection.Resolution.Selected ?: return null
        if (oneOffBaseUrl.isNotBlank() && !selected.explicit) return null
        return selected.connection
    }

    private fun currentBaseUrl(): String = currentConnection()?.baseUrl ?: oneOffBaseUrl

    /**
     * The connection line, plus whether *Save as environment…* has anything to offer.
     *
     * An ad-hoc URL is shown by host rather than in full — the row also carries two links — and it is
     * marked *not saved*, because an unnamed URL and a named environment are different things and the
     * difference decides whether the protection rules apply.
     */
    private fun updateConnectionStatus() {
        val connection = currentConnection()
        val available = AtlasEnvironments.getInstance().connections(ConnectionKind.WORK)
        populatingConnection = true
        try {
            connectionCombo.model = DefaultComboBoxModel<AtlasConnection?>().apply {
                // The one-off target stays a listed choice for as long as it exists, not just while it
                // happens to be selected: it is never added to the environment list, so if it vanished
                // from the picker the moment you looked at another environment, there would be no way
                // back to it short of pasting the link again.
                if (connection == null || oneOffBaseUrl.isNotBlank()) addElement(null)
                available.forEach { addElement(it) }
            }
            connectionCombo.selectedItem = connection
        } finally {
            populatingConnection = false
        }
        connectionCombo.toolTipText = connection?.let {
            ConnectionLabels.tooltip(ConnectionKind.WORK, AtlasConnectionSelection.Resolution.Selected(it, true))
        }
    }

    private fun chooseConnection(item: Any?) {
        val connection = item as? AtlasConnection
        if (connection == null) {
            // The one-off entry: going back to it is just dropping the environment selection, since
            // that is what makes it current again.
            if (oneOffBaseUrl.isNotBlank()) AtlasConnectionSelection.clear(project, ConnectionKind.WORK)
        } else {
            // Picking an environment *is* the switch — it never sends the user to Settings to first
            // create what the entry already promised.
            AtlasConnectionSelection.select(project, ConnectionKind.WORK, connection.id)
        }
        updateConnectionStatus()
    }

    private fun manageEnvironments() {
        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, com.flowable.atlas.settings.EnvironmentsConfigurable::class.java)
    }

    /**
     * One gesture, one dialog: paste a Work link and it resolves the app, the scope and the instance —
     * naming and checking the app when it is not one of your environments yet. This used to be a field,
     * a sentence explaining the field, a *Save as environment…* link and an "unsaved" state in the
     * picker, all sitting in the card whether or not anyone was pasting anything.
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
        val known = result.connectionId
        if (known != null) {
            oneOffBaseUrl = ""
            AtlasConnectionSelection.select(project, ConnectionKind.WORK, known)
        } else {
            // Nothing is created and nothing is written: the target lives here for this session, and its
            // credentials go to the in-memory session store as a basic Authorization header — the same
            // header a browser sign-in or a pasted cURL would have put there.
            oneOffBaseUrl = result.baseUrl
            AtlasConnectionSelection.clear(project, ConnectionKind.WORK)
            if (result.username.isNotBlank()) {
                val encoded = java.util.Base64.getEncoder()
                    .encodeToString("${result.username}:${result.password}".toByteArray())
                InspectSession.set(result.baseUrl, mapOf("Authorization" to "Basic $encoded"))
            }
        }
        result.parsed.scopeId?.let { scopeId ->
            result.parsed.scopeType?.let { scopeTypeCombo.selectedItem = it }
            scopeIdField.text = scopeId
            subScopeIdField.text = result.parsed.subScopeId ?: ""
        }
        updateConnectionStatus()
        val where = currentConnection()?.environmentName ?: BaseUrls.host(result.baseUrl).ifBlank { result.baseUrl }
        backendResultPane.showInfo("Using $where" + (result.parsed.scopeId?.let { " · $it" } ?: ""))
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
            javax.swing.JList(), connectionCombo.selectedItem as AtlasConnection?, -1, false, false,
        )
        return (component as? javax.swing.JLabel)?.text.orEmpty()
    }

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
            backendResultPane.showInfo("Choose an environment with Change…, or paste a Work URL above.")
            return
        }
        // Protection follows the URL, not the pointer: the card can hold a typed URL, so a guard keyed
        // on the selected connection would be walked around by pasting PROD's link.
        val protecting = AtlasProtection.protecting(
            baseUrl, ConnectionKind.WORK, AtlasEnvironments.getInstance().connections(),
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
            // The keychain can block, so the credential read happens here rather than on the EDT.
            val stored = runCatching { InspectCredentials.load(baseUrl) }
                .onFailure { LOG.warn("Could not read the Inspect password for $baseUrl from the PasswordSafe", it) }
                .getOrNull()
            val username = connection?.username?.takeIf { it.isNotBlank() } ?: stored?.userName.orEmpty()
            val req = InspectClient.Request(
                baseUrl = baseUrl,
                expression = expr,
                scopeType = scopeTypeCombo.selectedItem as InspectClient.ScopeType,
                scopeId = scopeIdField.text.trim(),
                subScopeId = subScopeIdField.text.trim().ifBlank { null },
                username = username,
                password = stored?.getPasswordAsString().orEmpty(),
                sessionHeaders = InspectSession.get(baseUrl),
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
