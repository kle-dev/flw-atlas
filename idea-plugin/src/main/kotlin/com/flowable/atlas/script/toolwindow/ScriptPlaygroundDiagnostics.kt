package com.flowable.atlas.script.toolwindow

import com.flowable.atlas.expr.ExprSeverity
import com.flowable.atlas.playground.PlaygroundMarkup
import com.flowable.atlas.playground.PlaygroundProblem
import com.flowable.atlas.playground.PlaygroundProblemsStrip
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
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.LanguageTextField
import com.intellij.util.SingleAlarm

/**
 * Live diagnostics of the Script Playground: the :core [ScriptValidator] (structural syntax) and
 * [ScriptVars] (touched variables) run debounced on a pooled thread over a text snapshot, and land
 * as editor markup ([PlaygroundMarkup]) + strip rows + variable chips. Manual markup, not the daemon:
 * annotator/daemon highlights do not reliably paint inside a [LanguageTextField] (see the expression
 * playground's [com.flowable.atlas.expr.toolwindow.PlaygroundDiagnostics], whose idioms this class
 * copies) — and the structural validator works even where the Groovy/JS plugin is absent.
 */
internal class ScriptPlaygroundDiagnostics(
    private val project: Project,
    private val field: LanguageTextField,
    private val strip: PlaygroundProblemsStrip,
    private val chips: List<ScriptVarChipsPanel>,
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
    private val markup = PlaygroundMarkup()
    private var lastComputed: Computed? = null

    init {
        Disposer.register(parentDisposable, this)
    }

    fun scheduleRevalidate() = alarm.cancelAndRequest()

    /** Called from the field's `addSettingsProvider` — i.e. whenever a (new) editor materializes. */
    fun editorAvailable(editor: EditorEx) {
        // markup of the previous editor died with it — forget it and re-paint onto the new one
        markup.attach(editor)
        applyComputed()
    }

    override fun dispose() = markup.clear()

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
        markup.clear()
        val c = lastComputed ?: return
        strip.setRows(
            c.problems.map { PlaygroundProblemsStrip.Row(it.severity == ExprSeverity.ERROR, it.message, it.startOffset) },
            ::navigateTo,
        )
        val roots = ScriptBindingsCatalog.rootsFor(host.context).values.filterNot { it.hidden }
        // Two chip panels — what the context provides, what the script does — fed from one pass so
        // chips and squiggles never drift apart.
        for (panel in chips) {
            panel.setVars(
                c.vars,
                bindings = roots.filterNot { it.bean }.map { it.name }.sorted(),
                // the platform beans get their own capped row (+N more tooltip lists the rest)
                beans = roots.filter { it.bean }.map { it.name }.sorted(),
            )
        }
        markup.paint(c.problems.map { PlaygroundProblem(it.startOffset, it.endOffset, it.message, it.severity == ExprSeverity.ERROR) })
    }

    private fun navigateTo(offset: Int) {
        val editor = field.editor ?: return
        editor.caretModel.moveToOffset(offset.coerceIn(0, editor.document.textLength))
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        IdeFocusManager.getInstance(project).requestFocus(field, true)
    }

    internal companion object {
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
