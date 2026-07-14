package com.flowable.atlas.expr.toolwindow

import com.flowable.atlas.expr.ExprProblem
import com.flowable.atlas.expr.ExprSeverity
import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.expr.ExpressionValidator
import com.flowable.atlas.expr.catalog.FlowableCustomFunctions
import com.flowable.atlas.expr.eval.EvalResult
import com.flowable.atlas.expr.eval.FrontendExpressionEvaluator
import com.flowable.atlas.expr.eval.PayloadScopePath
import com.flowable.atlas.expr.eval.PayloadScopes
import com.flowable.atlas.expr.eval.TraceEntry
import com.flowable.atlas.expr.eval.TraceNodeKind
import com.flowable.atlas.expr.eval.TraceOutcome
import com.flowable.atlas.expr.eval.TracedEvaluation
import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.codeInsight.hints.presentation.PresentationRenderer
import com.intellij.icons.AllIcons
import com.intellij.idea.AppMode
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.LanguageTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.SingleAlarm
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The playground's validation/evaluation pipeline: one debounced pass computes structural syntax
 * errors + semantic findings (allowlist-filtered) and — for the frontend dialect — the traced
 * payload evaluation, then paints everything into the expression field.
 *
 * Annotator/daemon highlights do not reliably paint inside a [LanguageTextField], so both the
 * squiggles and the `= value` sub-expression hints are applied MANUALLY: [RangeHighlighter]s on the
 * editor's own markup model and inline [Inlay]s. The editor materializes lazily (and is re-created
 * on a dialect switch); [editorAvailable] re-applies the last computed state whenever that happens.
 *
 * Threading mirrors `AtlasHubPanel`: alarm on the EDT → gather on a pooled thread → apply on the
 * EDT, guarded by the document's modification stamp so stale results are dropped.
 */
internal class PlaygroundDiagnostics(
    private val project: Project,
    private val field: LanguageTextField,
    private val strip: PlaygroundProblemsStrip,
    private val host: Host,
    parentDisposable: Disposable,
) : Disposable {

    /** What the pipeline needs from the panel; called on the EDT only. */
    internal interface Host {
        val dialect: ExpressionDialect
        val payloadText: String

        /** The frontend payload-scope path as typed (`orders[2].items[0]`-style, blank = root). */
        val frontendScopeText: String
        val showSubEvaluations: Boolean

        /** Frontend evaluation result of the last pass — `null` means "expression is blank". Not
         *  called while the backend dialect is active (that result pane is driven by Inspect calls). */
        fun onFrontendResult(result: EvalResult?)

        /** Scope-path status of the last pass — `null` when no scope is set (or the backend dialect
         *  is active). Drives the scope field's error outline and the payload-editor highlight. */
        fun onScopeStatus(status: ScopeStatus?)
    }

    /** Outcome of checking the typed scope path against the current payload. */
    internal sealed interface ScopeStatus {
        data class Valid(val path: PayloadScopePath) : ScopeStatus
        data class Invalid(val message: String) : ScopeStatus
    }

    private class Computed(
        val text: String,
        val stamp: Long,
        val dialect: ExpressionDialect,
        val problems: List<ExprProblem>,
        val trace: TracedEvaluation?,
        val scopeStatus: ScopeStatus?,
    )

    private val alarm = SingleAlarm(::revalidate, 250, this)
    private val highlighters = mutableListOf<RangeHighlighter>()
    private val inlays = mutableListOf<Inlay<*>>()

    /** On a Remote-Dev host a custom-painted inlay never reaches the thin client, so the inline
     *  `= value` badges are invisible there. Mirror the same values into a plain-Swing fallback
     *  ([PlaygroundProblemsStrip]) — Swing renders fine over the Remote-Dev protocol. */
    private val remoteDevHost = AppMode.isRemoteDevHost()
    private var appliedEditor: EditorEx? = null
    private var lastComputed: Computed? = null
    private var selectionEditor: Editor? = null
    private val selectionListener = object : SelectionListener {
        override fun selectionChanged(e: SelectionEvent) = onSelectionChanged(e)
    }

    init {
        Disposer.register(parentDisposable, this)
    }

    fun scheduleRevalidate() = alarm.cancelAndRequest()

    /**
     * Re-apply just the sub-expression `= value` inlays from the last computed trace, synchronously on
     * the EDT. The toolbar's "Show Sub-Expression Values" toggle only flips inlay visibility — the
     * trace is already in [lastComputed], so there is nothing to re-evaluate. Doing this inline avoids
     * the 250 ms debounce + pooled round-trip whose result the stale-guard (or an editor relayout that
     * happens to overlap it) can silently drop, which left the hints intermittently missing after a
     * toggle. Highlighters and the result pane are left untouched. If the editor is momentarily absent
     * the next [editorAvailable] re-applies everything, so no state is lost.
     */
    fun refreshSubEvaluations() = renderSubEvaluations()

    /** Called from the field's `addSettingsProvider` — i.e. whenever a (new) editor materializes. */
    fun editorAvailable(editor: EditorEx) {
        selectionEditor?.let { old -> if (!old.isDisposed) old.selectionModel.removeSelectionListener(selectionListener) }
        editor.selectionModel.addSelectionListener(selectionListener)
        selectionEditor = editor
        // markup of the previous editor died with it — forget it and re-paint onto the new one
        highlighters.clear()
        inlays.clear()
        appliedEditor = editor
        applyComputed()
    }

    override fun dispose() {
        selectionEditor?.let { if (!it.isDisposed) it.selectionModel.removeSelectionListener(selectionListener) }
        selectionEditor = null
        clearMarkup()
    }

    // ---- pipeline ---------------------------------------------------------------------------

    private fun revalidate() {
        val text = field.text
        val stamp = field.document.modificationStamp
        val dialect = host.dialect
        val payload = if (dialect == ExpressionDialect.FRONTEND) host.payloadText else ""
        val scopeText = if (dialect == ExpressionDialect.FRONTEND) host.frontendScopeText else ""
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val allowlist = FlowableAtlasProjectSettings.getInstance(project)
            // the first catalog() call scans the file system / index — pooled + read action, never the EDT
            val custom = ApplicationManager.getApplication().runReadAction(
                Computable { FlowableCustomFunctions.getInstance(project).catalog() },
            )
            val problems = (
                ExpressionValidator.validateSyntax(text, dialect) +
                    ExpressionValidator.validateSemantics(text, dialect, custom).filterNot { allowlist.isAllowlisted(it) }
                ).sortedBy { it.startOffset }
            val (scopePath, scopeStatus) = checkScope(dialect, scopeText, payload)
            val trace = when {
                dialect != ExpressionDialect.FRONTEND || text.isBlank() -> null
                // an unparseable path never reaches the evaluator — surface it as the result, same
                // loud policy as an unresolvable path (which the evaluator itself reports)
                scopePath == null && scopeStatus is ScopeStatus.Invalid ->
                    TracedEvaluation(EvalResult.Err(scopeStatus.message), emptyList())
                else -> FrontendExpressionEvaluator.evaluateTraced(text, payload, scopePath)
            }
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                // stale (text/dialect/payload/scope changed since) → drop; the pending alarm re-runs
                if (field.document.modificationStamp != stamp || host.dialect != dialect) return@invokeLater
                if (dialect == ExpressionDialect.FRONTEND && (host.payloadText != payload || host.frontendScopeText != scopeText)) return@invokeLater
                lastComputed = Computed(text, stamp, dialect, problems, trace, scopeStatus)
                applyComputed()
            }, ModalityState.any())
        }
    }

    /**
     * Parse + resolve the typed scope path against the payload. Returns the path to evaluate with
     * (null = evaluate at the root) and the status for the UI (null = no scope set). The resolution
     * here only feeds the field outline / highlight — the evaluator re-resolves and reports its own
     * error, so the result pane and the status can't drift apart.
     */
    private fun checkScope(dialect: ExpressionDialect, scopeText: String, payload: String): Pair<PayloadScopePath?, ScopeStatus?> {
        if (dialect != ExpressionDialect.FRONTEND || scopeText.isBlank()) return null to null
        val path = when (val parsed = PayloadScopePath.parse(scopeText)) {
            is PayloadScopePath.ParseResult.Err -> return null to ScopeStatus.Invalid(parsed.message)
            is PayloadScopePath.ParseResult.Ok -> parsed.path
        }
        if (path.isRoot) return null to null
        val payloadValue: Any? = try {
            if (payload.isBlank()) emptyMap<String, Any?>() else MiniJson.parse(payload)
        } catch (e: MiniJson.JsonException) {
            return path to ScopeStatus.Invalid("Payload is not valid JSON: ${e.message}")
        }
        return when (val r = PayloadScopes.resolve(payloadValue, path)) {
            is PayloadScopes.Resolution.Resolved -> path to ScopeStatus.Valid(path)
            is PayloadScopes.Resolution.NotFound -> path to ScopeStatus.Invalid(r.message)
        }
    }

    private fun applyComputed() {
        clearMarkup()
        val c = lastComputed ?: return
        strip.setProblems(c.problems, ::navigateTo)
        strip.setSelectionInfo(null)
        if (c.dialect == ExpressionDialect.FRONTEND) {
            host.onFrontendResult(c.trace?.result)
        }
        host.onScopeStatus(if (c.dialect == ExpressionDialect.FRONTEND) c.scopeStatus else null)
        (field.editor as? EditorEx)?.let { editor ->
            appliedEditor = editor
            applyHighlighters(editor, c.problems)
        }
        renderSubEvaluations()
    }

    private fun clearMarkup() {
        appliedEditor?.takeUnless { it.isDisposed }?.let { editor ->
            for (h in highlighters) if (h.isValid) editor.markupModel.removeHighlighter(h)
        }
        highlighters.clear()
        for (i in inlays) if (i.isValid) Disposer.dispose(i)
        inlays.clear()
    }

    private fun applyHighlighters(editor: EditorEx, problems: List<ExprProblem>) {
        val length = editor.document.textLength
        if (length == 0) return
        for (p in problems) {
            var start = p.startOffset.coerceIn(0, length)
            var end = p.endOffset.coerceIn(0, length)
            if (start >= end) {                      // zero-length finding → mark one char
                end = (start + 1).coerceAtMost(length)
                start = end - 1
            }
            val error = p.severity == ExprSeverity.ERROR
            // Explicit attributes rather than the scheme's ERRORS_ATTRIBUTES: at the playground's small
            // font the scheme's thin wave is barely visible, and error ranges are often a single char
            // (e.g. the unclosed '('). A bold underline + a faint background tint reads clearly even on
            // one character, and an error-stripe mark shows in the gutter. JBColors adapt to the theme.
            val attrs = TextAttributes().apply {
                effectType = EffectType.BOLD_LINE_UNDERSCORE
                effectColor = if (error) ERROR_COLOR else WARN_COLOR
                backgroundColor = if (error) ERROR_BG else WARN_BG
            }
            val layer = if (error) HighlighterLayer.ERROR else HighlighterLayer.WARNING
            val h = editor.markupModel.addRangeHighlighter(start, end, layer, attrs, HighlighterTargetArea.EXACT_RANGE)
            h.errorStripeTooltip = p.message
            h.setErrorStripeMarkColor(if (error) ERROR_COLOR else WARN_COLOR)
            highlighters += h
        }
    }

    private fun navigateTo(offset: Int) {
        val editor = field.editor ?: return
        editor.caretModel.moveToOffset(offset.coerceIn(0, editor.document.textLength))
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        IdeFocusManager.getInstance(project).requestFocus(field, true)
    }

    // ---- sub-expression value inlays (frontend) ----------------------------------------------

    private companion object {
        // Problem highlight colors (light, dark) — see [applyHighlighters].
        val ERROR_COLOR = JBColor(0xD11E1E, 0xF25555)
        val WARN_COLOR = JBColor(0xC28A00, 0xE0A93F)
        val ERROR_BG = JBColor(0xFFE0E0, 0x5A2D2D)
        val WARN_BG = JBColor(0xFFF3D6, 0x4A3F24)

        /** Node kinds worth a hint — a `= value` after every literal or member read is noise. */
        val HINTED_KINDS = setOf(
            TraceNodeKind.BINARY, TraceNodeKind.CALL, TraceNodeKind.PIPE,
            TraceNodeKind.TERNARY, TraceNodeKind.INDEX,
        )
        const val MAX_INLAYS = 8
        const val MAX_VALUE_LENGTH = 40
        const val MIN_NODE_SPAN = 3
    }

    /**
     * Rebuild the sub-expression `= value` hints from the last computed trace, on the EDT. Two
     * surfaces, both gated on the "Show Sub-Expression Values" toggle:
     *  - inline [Inlay]s via the platform's [PresentationRenderer] — a serializable presentation, so
     *    it renders locally AND stands a chance on a Remote-Dev thin client, unlike a raw custom paint;
     *  - on a Remote-Dev host, the very same values as guaranteed plain-Swing rows in [strip].
     * The editor may be momentarily absent (it materializes lazily and is re-created on a dialect
     * switch); the inlays are then skipped and the next [editorAvailable] re-applies them.
     */
    private fun renderSubEvaluations() {
        for (i in inlays) if (i.isValid) Disposer.dispose(i)
        inlays.clear()
        val c = lastComputed
        val hints = if (host.showSubEvaluations && c?.trace != null)
            computeSubHints(c.text, c.trace.entries) else emptyList()
        (field.editor as? EditorEx)?.let { editor ->
            val factory = PresentationFactory(editor)
            val length = editor.document.textLength
            for (h in hints) {
                val presentation = factory.roundWithBackground(factory.smallText(h.label))
                val inlay = editor.inlayModel.addInlineElement(
                    h.anchor.coerceIn(0, length), true, PresentationRenderer(presentation),
                ) ?: continue
                inlays += inlay
            }
        }
        if (remoteDevHost) strip.setSubEvaluations(hints.map { "${it.exprText}${it.label}" })
    }

    /** One sub-expression hint: where the inline badge anchors, the node's own source text, and the
     *  ` = value` label — shared verbatim by the inline inlays and the Remote-Dev Swing fallback. */
    private data class SubHint(val anchor: Int, val exprText: String, val label: String)

    /** Pick the trace nodes worth a hint (the same filter the inlays always used) and format each. */
    private fun computeSubHints(text: String, entries: List<TraceEntry>): List<SubHint> {
        val parenMatch = matchParens(text)
        val hints = ArrayList<SubHint>()
        for (entry in entries) {
            if (hints.size >= MAX_INLAYS) break
            if (entry.depth == 0) continue                        // the root's value lives in the result pane
            if (entry.kind !in HINTED_KINDS) continue
            if (entry.end - entry.start < MIN_NODE_SPAN) continue
            val (anchor, parenthesized) = anchorAfterParens(text, entry, parenMatch)
            if (entry.depth > 2 && !parenthesized) continue
            val label = when (val o = entry.outcome) {
                is TraceOutcome.Value -> " = ${truncateMiddle(display(o.value), MAX_VALUE_LENGTH)}"
                is TraceOutcome.Unavailable -> " = ?"
                is TraceOutcome.Error, TraceOutcome.NotEvaluated -> continue   // errors are squiggled already
            }
            val s = entry.start.coerceIn(0, text.length)
            val e = entry.end.coerceIn(s, text.length)
            hints += SubHint(anchor, text.substring(s, e), label)
        }
        return hints
    }

    /** Close-paren index → its open-paren index; quick scan that skips string literals. */
    private fun matchParens(text: String): Map<Int, Int> {
        val match = HashMap<Int, Int>()
        val stack = ArrayDeque<Int>()
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quote != null -> if (ch == '\\') i++ else if (ch == quote) quote = null
                ch == '\'' || ch == '"' -> quote = ch
                ch == '(' -> stack.addLast(i)
                ch == ')' -> stack.removeLastOrNull()?.let { match[i] = it }
            }
            i++
        }
        return match
    }

    /**
     * `(1+1)` has no paren node in the AST and the [TraceEntry] offsets exclude the parens — hop the
     * anchor over every closing paren whose matching `(` sits before the node, so the hint reads
     * `(1+1) = 2`, not `(1+1 = 2)`. Doubles as the "is parenthesized" detector for the noise filter.
     */
    private fun anchorAfterParens(text: String, entry: TraceEntry, parenMatch: Map<Int, Int>): Pair<Int, Boolean> {
        var anchor = entry.end
        var parenthesized = false
        var i = entry.end
        while (true) {
            while (i < text.length && text[i] == ' ') i++
            if (i < text.length && text[i] == ')' && (parenMatch[i] ?: Int.MAX_VALUE) < entry.start) {
                i++
                anchor = i
                parenthesized = true
            } else break
        }
        return anchor to parenthesized
    }

    // ---- evaluate selection -------------------------------------------------------------------

    private fun onSelectionChanged(e: SelectionEvent) {
        val c = lastComputed ?: return
        val trace = c.trace ?: run { strip.setSelectionInfo(null); return }
        val range = e.newRange
        if (range.isEmpty) { strip.setSelectionInfo(null); return }
        val normalized = normalizeSelection(c.text, range.startOffset, range.endOffset)
        val entry = normalized?.let { (s, end) ->
            trace.entries.firstOrNull { it.start == s && it.end == end && it.outcome is TraceOutcome.Value }
        }
        strip.setSelectionInfo(entry?.let {
            val value = (it.outcome as TraceOutcome.Value).value
            "Selection = ${truncateMiddle(display(value), MAX_VALUE_LENGTH)}   (${typeName(value)})"
        })
    }

    /** Trim whitespace and strip matching outer parens so selecting `(2+2)` finds the `2+2` node. */
    private fun normalizeSelection(text: String, startIn: Int, endIn: Int): Pair<Int, Int>? {
        if (startIn < 0 || endIn > text.length) return null
        var s = startIn
        var e = endIn
        fun trim() {
            while (s < e && text[s].isWhitespace()) s++
            while (e > s && text[e - 1].isWhitespace()) e--
        }
        trim()
        val parenMatch = matchParens(text)
        while (e - s >= 2 && text[s] == '(' && text[e - 1] == ')' && parenMatch[e - 1] == s) {
            s++; e--; trim()
        }
        return if (s < e) s to e else null
    }

    // ---- value rendering ------------------------------------------------------------------------

    private fun display(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"$value\""
        else -> MiniJson.stringify(value)
    }

    private fun typeName(value: Any?): String = when (value) {
        null -> "null"
        is String -> "string"
        is Double -> "number"
        is Boolean -> "boolean"
        is List<*> -> "array"
        is Map<*, *> -> "object"
        else -> value.javaClass.simpleName
    }

    private fun truncateMiddle(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max / 2) + "…" + s.takeLast(max / 2 - 1)
}

/**
 * Persistent message rows under the expression field — replaces the old monospace `^`-caret text
 * area and the orange one-line label: the exact offset is now marked by the in-editor squiggle, so
 * the strip carries the message (clicking a row jumps to the offset). Caps at [MAX_ROWS] rows plus
 * a "+N more" summary; an optional transient "Selection = …" info row sits on top, and — on a
 * Remote-Dev host, where the inline `= value` badges can't paint — the sub-expression values are
 * mirrored here as plain rows (see [setSubEvaluations]).
 */
internal class PlaygroundProblemsStrip : JPanel() {

    private companion object { const val MAX_ROWS = 3 }

    private var selectionInfo: String? = null
    private var problems: List<ExprProblem> = emptyList()
    private var subEvaluations: List<String> = emptyList()
    private var navigate: (Int) -> Unit = {}

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 2, 0, 2)
        isVisible = false
    }

    fun setProblems(problems: List<ExprProblem>, navigate: (Int) -> Unit) {
        this.problems = problems
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
        for (p in problems.take(MAX_ROWS)) add(problemRow(p))
        if (problems.size > MAX_ROWS) {
            add(JBLabel("+${problems.size - MAX_ROWS} more").apply {
                foreground = JBColor.GRAY
                toolTipText = problems.drop(MAX_ROWS).joinToString("<br>", "<html>", "</html>") { it.message }
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
    private fun problemRow(p: ExprProblem): JPanel {
        val severityIcon = if (p.severity == ExprSeverity.ERROR) AllIcons.General.Error else AllIcons.General.Warning
        val link = HyperlinkLabel(p.message).apply {
            addHyperlinkListener { navigate(p.startOffset) }
        }
        val jump = JBLabel(AllIcons.Actions.EditSource).apply {
            toolTipText = "Jump to the problem"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = navigate(p.startOffset)
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
