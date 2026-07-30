package com.flowable.atlas.script.toolwindow

import com.flowable.atlas.expr.toolwindow.PlaygroundProblemsStrip
import com.flowable.atlas.script.ScriptContext
import com.flowable.atlas.script.completion.ScriptScope
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.command.undo.UndoUtil
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.JBColor
import com.intellij.ui.LanguageTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The Script Playground: paste or write a Groovy/JavaScript/Python script and get the IDE's real
 * language editing (completion, syntax coloring — where the language plugin is available), live
 * structural validation from :core's ScriptValidator (squiggles + problem rows, exactly the checks
 * the CLI/explorer run), and the scope variables the script touches. The toolbar loads any script
 * task / listener / action-bot script straight from the project's models ([ScriptPicker]).
 *
 * Sibling of [com.flowable.atlas.expr.toolwindow.FlowableExpressionPanel] (same skeleton, no
 * evaluation machinery); hosted as the "Scripts" tab of the Flowable Expressions tool window.
 * Construction must stay cheap and index-free — the factory runs synchronously on the EDT.
 */
class FlowableScriptPanel(val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val state = FlowableScriptPlaygroundState.getInstance(project)

    /** The raw scriptFormat string driving validation; the combo shows its [PlaygroundScriptLanguage]. */
    var scriptFormat: String = state.format
        private set

    /** Which Flowable context the script is validated against (decides the bound root objects). */
    var scriptContext: ScriptContext = state.context
        private set

    val language: PlaygroundScriptLanguage get() = PlaygroundScriptLanguage.fromFormat(scriptFormat)

    private val field: LanguageTextField =
        LanguageTextField(language.ideLanguage(), project, state.script, false).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            minimumSize = Dimension(JBUI.scale(120), JBUI.scale(80))
            addSettingsProvider { editor ->
                // Same rationale as the expression playground: the document isn't file-backed, so
                // undo must be enabled explicitly; this provider re-runs after a language switch
                // swaps the document, re-wiring undo and diagnostics for the new editor.
                UndoUtil.enableUndoFor(editor.document)
                editor.setVerticalScrollbarVisible(true)
                editor.setHorizontalScrollbarVisible(true)
                editor.setBorder(JBUI.Borders.empty(4))
                editor.settings.apply {
                    isLineNumbersShown = true         // scripts are multi-line — offsets matter
                    isFoldingOutlineShown = false
                    isLineMarkerAreaShown = false
                    isUseSoftWraps = false
                    isCaretRowShown = false
                    additionalLinesCount = 1
                    additionalColumnsCount = 2
                }
                diagnostics.editorAvailable(editor as EditorEx)
            }
            addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    state.script = text
                    diagnostics.scheduleRevalidate()
                }
            })
        }

    private val strip = PlaygroundProblemsStrip()
    private val chips = ScriptVarChipsPanel()
    private val diagnostics = ScriptPlaygroundDiagnostics(
        project, field, strip, chips,
        object : ScriptPlaygroundDiagnostics.Host {
            override val format: String get() = this@FlowableScriptPanel.scriptFormat
            override val context: ScriptContext get() = this@FlowableScriptPanel.scriptContext
        },
        this,
    )

    init {
        toolbar = ActionManager.getInstance()
            .createActionToolbar("FlowableScriptPlayground", buildToolbarGroup(), true)
            .also { it.targetComponent = this }
            .component
        val editorSection = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 6, 4, 6)
            add(field, BorderLayout.CENTER)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(strip)
                add(chips)
            }, BorderLayout.SOUTH)
        }
        setContent(editorSection)
        applyContextStamp()
        diagnostics.scheduleRevalidate()
    }

    val focusComponent: JComponent get() = this.field

    override fun dispose() {}   // everything disposable hangs off Disposer chains

    private fun buildToolbarGroup(): DefaultActionGroup {
        val group = DefaultActionGroup()
        group.add(ScriptLanguageComboBoxAction(this))
        group.add(ScriptContextComboBoxAction(this))
        group.addSeparator()
        group.add(LoadScriptFromModelAction(this))
        return group
    }

    fun switchLanguage(newLanguage: PlaygroundScriptLanguage) = setFormat(newLanguage.format)

    fun switchContext(newContext: ScriptContext) {
        if (newContext == scriptContext) return
        scriptContext = newContext
        state.context = newContext
        applyContextStamp()
        diagnostics.scheduleRevalidate()
    }

    /** The picker's entry point: put [body] into the editor and validate it under [format]/[context]. */
    fun loadScript(body: String, format: String?, context: ScriptContext = ScriptContext.UNKNOWN) {
        format?.trim()?.lowercase()?.ifEmpty { null }?.let { setFormat(it) }
        if (context != ScriptContext.UNKNOWN) switchContext(context)
        field.text = body
        diagnostics.scheduleRevalidate()
        IdeFocusManager.getInstance(project).requestFocus(field, true)
    }

    /** Swap language + file type on the ONE editor field — no field re-creation; the field re-runs
     *  the settings provider itself, which re-wires undo and diagnostics (same idiom as the
     *  expression playground's dialect switch). Accepts raw formats like "js" — validation keeps
     *  the verbatim string, the combo displays its family. */
    private fun setFormat(newFormat: String) {
        if (newFormat == scriptFormat) return
        scriptFormat = newFormat
        state.format = newFormat
        val document = LanguageTextField.createDocument(
            field.text, language.ideLanguage(), project, LanguageTextField.SimpleDocumentCreator())
        field.setNewDocumentAndFileType(language.fileType(), document)
        applyContextStamp()   // new document = new PsiFile — re-stamp the completion context
        diagnostics.scheduleRevalidate()
    }

    /** Tell the bindings completion which context to offer (see [ScriptScope.CONTEXT_KEY]) and mark
     *  the scratch file for whole-project import resolution — the script twin of the expression
     *  playground's `applyScope()`. */
    private fun applyContextStamp() {
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(field.document) ?: return
        psiFile.putUserData(ScriptScope.CONTEXT_KEY, scriptContext)
        psiFile.virtualFile?.putUserData(ScriptScope.PLAYGROUND_FILE, true)
    }
}
