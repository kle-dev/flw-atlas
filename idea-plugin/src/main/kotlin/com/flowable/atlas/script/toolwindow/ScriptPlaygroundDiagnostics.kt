package com.flowable.atlas.script.toolwindow

import com.flowable.atlas.expr.ExprSeverity
import com.flowable.atlas.expr.toolwindow.PlaygroundProblemsStrip
import com.flowable.atlas.parsing.ScriptVarUse
import com.flowable.atlas.parsing.ScriptVars
import com.flowable.atlas.script.ScriptBindingsCatalog
import com.flowable.atlas.script.ScriptContext
import com.flowable.atlas.script.ScriptProblem
import com.flowable.atlas.script.ScriptValidator
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.JBColor
import com.intellij.ui.LanguageTextField
import com.intellij.util.SingleAlarm

/**
 * Live diagnostics of the Script Playground: the :core [ScriptValidator] (structural syntax) and
 * [ScriptVars] (touched variables) run debounced on a pooled thread over a text snapshot, and land
 * as manual editor markup + strip rows + variable chips. Manual markup, not the daemon: annotator/
 * daemon highlights do not reliably paint inside a [LanguageTextField] (see the expression
 * playground's [com.flowable.atlas.expr.toolwindow.PlaygroundDiagnostics], whose idioms this class
 * copies) — and the structural validator works even where the Groovy/JS plugin is absent.
 */
internal class ScriptPlaygroundDiagnostics(
    private val project: Project,
    private val field: LanguageTextField,
    private val strip: PlaygroundProblemsStrip,
    private val chips: ScriptVarChipsPanel,
    private val host: Host,
    parentDisposable: Disposable,
) : Disposable {

    /** EDT-only view of the panel's current script format + context. */
    internal interface Host {
        val format: String
        val context: ScriptContext
    }

    private class Computed(val stamp: Long, val problems: List<ScriptProblem>, val vars: ScriptVarUse)

    private val alarm = SingleAlarm(::revalidate, 250, this)
    private val highlighters = mutableListOf<RangeHighlighter>()
    private var appliedEditor: EditorEx? = null
    private var lastComputed: Computed? = null

    init {
        Disposer.register(parentDisposable, this)
    }

    fun scheduleRevalidate() = alarm.cancelAndRequest()

    /** Called from the field's `addSettingsProvider` — i.e. whenever a (new) editor materializes. */
    fun editorAvailable(editor: EditorEx) {
        // markup of the previous editor died with it — forget it and re-paint onto the new one
        highlighters.clear()
        appliedEditor = editor
        applyComputed()
    }

    override fun dispose() = clearMarkup()

    // ---- pipeline ---------------------------------------------------------------------------

    private fun revalidate() {
        val text = field.text
        val stamp = field.document.modificationStamp
        val format = host.format
        val context = host.context
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            // pure CPU over the snapshot — no VFS/PSI/index, so no read action
            val (problems, vars) = computeFindings(text, format, context)
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                // stale (text, language or context changed since) → drop; the pending alarm re-runs
                if (field.document.modificationStamp != stamp || host.format != format ||
                    host.context != context) return@invokeLater
                lastComputed = Computed(stamp, problems, vars)
                applyComputed()
            }, ModalityState.any())
        }
    }

    private fun applyComputed() {
        clearMarkup()
        val c = lastComputed ?: return
        strip.setRows(
            c.problems.map { PlaygroundProblemsStrip.Row(it.severity == ExprSeverity.ERROR, it.message, it.startOffset) },
            ::navigateTo,
        )
        val roots = ScriptBindingsCatalog.rootsFor(host.context).values.filterNot { it.hidden }
        chips.setVars(
            c.vars,
            bindings = roots.filterNot { it.bean }.map { it.name }.sorted(),
            // the platform beans get their own capped row (+N more tooltip lists the rest)
            beans = roots.filter { it.bean }.map { it.name }.sorted(),
        )
        (field.editor as? EditorEx)?.let { editor ->
            appliedEditor = editor
            applyHighlighters(editor, c.problems)
        }
    }

    private fun clearMarkup() {
        appliedEditor?.takeUnless { it.isDisposed }?.let { editor ->
            for (h in highlighters) if (h.isValid) editor.markupModel.removeHighlighter(h)
        }
        highlighters.clear()
    }

    private fun applyHighlighters(editor: EditorEx, problems: List<ScriptProblem>) {
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
            // Same explicit attributes as the expression playground: a bold underline + faint tint
            // reads clearly even on a one-character range (the unclosed '('), where the scheme's
            // thin wave would vanish at this font size.
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

    internal companion object {
        // Problem highlight colors (light, dark) — same values as the expression playground.
        val ERROR_COLOR = JBColor(0xD11E1E, 0xF25555)
        val WARN_COLOR = JBColor(0xC28A00, 0xE0A93F)
        val ERROR_BG = JBColor(0xFFE0E0, 0x5A2D2D)
        val WARN_BG = JBColor(0xFFF3D6, 0x4A3F24)

        /** The pooled pass, as a pure seam for tests: validator + vars over one snapshot.
         *  `formatRequired = false` — a playground must not nag about empty bodies or a missing
         *  format while the user is still typing. */
        fun computeFindings(
            text: String, format: String, context: ScriptContext = ScriptContext.UNKNOWN,
        ): Pair<List<ScriptProblem>, ScriptVarUse> =
            ScriptValidator.validate(text, format, formatRequired = false, context = context) to
                ScriptVars.analyze(text, format)
    }
}
