package com.flowable.atlas.script.toolwindow

import com.flowable.atlas.expr.toolwindow.PlaygroundSettingsGroup
import com.flowable.atlas.expr.toolwindow.StackPanelsToggle
import com.flowable.atlas.playground.ContextPanel
import com.flowable.atlas.playground.PlaygroundEditors
import com.flowable.atlas.playground.PlaygroundProblemsStrip
import com.flowable.atlas.playground.PlaygroundShell
import com.flowable.atlas.script.ScriptContext
import com.flowable.atlas.script.completion.ScriptScope
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.LanguageTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The Script Playground on the shared [PlaygroundShell]: the script on one side with its problems under
 * it, and on the other what the selected context *provides* (its bindings and beans, as clickable chips)
 * over what the script *does* (the variables it writes through the API and the ones it likely reads).
 * The IDE's real language editing where the language plugin is present, :core's structural validation
 * everywhere. The toolbar loads any script task / listener / action-bot script straight from the
 * project's models ([ScriptPicker]), or one of the worked examples ([ScriptExamples]).
 *
 * Construction must stay cheap and index-free — the factory runs synchronously on the EDT.
 */
class FlowableScriptPanel(val project: Project, stackedByDefault: Boolean = true) : JPanel(BorderLayout()), Disposable {

    private val state = FlowableScriptPlaygroundState.getInstance(project)
    private val shell = PlaygroundShell(project, "scripts", stackedByDefault)

    /** The raw scriptFormat string driving validation; the combo shows its [PlaygroundScriptLanguage]. */
    var scriptFormat: String = state.format
        private set

    /** Which Flowable context the script is validated against (decides the bound root objects). */
    var scriptContext: ScriptContext = state.context
        private set

    val language: PlaygroundScriptLanguage get() = PlaygroundScriptLanguage.fromFormat(scriptFormat)

    private val field: LanguageTextField = PlaygroundEditors.create(
        project, language.ideLanguage(), state.script, lineNumbers = true, minHeight = 80,
        onEditor = { diagnostics.editorAvailable(it) },
        onChange = {
            state.script = scriptText()
            diagnostics.scheduleRevalidate()
        },
    )

    private val strip = PlaygroundProblemsStrip()
    private val provided = ScriptVarChipsPanel(onPick = ::insertAtCaret, showUsage = false, showContext = true)
    private val touched = ScriptVarChipsPanel(onPick = ::insertAtCaret, showUsage = true, showContext = false)
    private val context = ContextPanel("Context", expandedInitially = true) {}
    private val diagnostics = ScriptPlaygroundDiagnostics(
        project, field, strip, listOf(provided, touched),
        object : ScriptPlaygroundDiagnostics.Host {
            override val format: String get() = this@FlowableScriptPanel.scriptFormat
            override val context: ScriptContext get() = this@FlowableScriptPanel.scriptContext
        },
        this,
    )

    var stacked: Boolean
        get() = shell.stacked
        set(value) { shell.stacked = value }

    init {
        shell.toolbar = ActionManager.getInstance()
            .createActionToolbar("FlowableScriptPlayground", buildToolbarGroup(), true)
            .also { it.targetComponent = shell }
            .component
        shell.setEditorSide(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 6, 4, 6)
            add(field, BorderLayout.CENTER)
            add(strip, BorderLayout.SOUTH)
        })
        context.setBody(provided)
        shell.setContext(context)
        shell.setResult(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 6, 6, 6)
            add(JBLabel("What the script touches").apply {
                font = JBUI.Fonts.smallFont()
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.empty(0, 2, 3, 0)
            }, BorderLayout.NORTH)
            add(touched, BorderLayout.CENTER)
        })
        add(shell, BorderLayout.CENTER)
        applyContextStamp()
        updateSummary()
        diagnostics.scheduleRevalidate()
    }

    val focusComponent: JComponent get() = this.field

    private fun scriptText(): String = this.field.text

    override fun dispose() {}   // everything disposable hangs off Disposer chains

    private fun buildToolbarGroup(): DefaultActionGroup {
        val group = DefaultActionGroup()
        group.add(ScriptLanguageComboBoxAction(this))
        group.add(ScriptContextComboBoxAction(this))
        group.addSeparator()
        group.add(LoadScriptFromModelAction(this))
        group.add(LoadExampleScriptAction(this))
        group.addSeparator()
        group.add(PlaygroundSettingsGroup(project, StackPanelsToggle({ stacked }, { stacked = it }), expressionSettings = false))
        return group
    }

    fun switchLanguage(newLanguage: PlaygroundScriptLanguage) = setFormat(newLanguage.format)

    fun switchContext(newContext: ScriptContext) {
        if (newContext == scriptContext) return
        scriptContext = newContext
        state.context = newContext
        applyContextStamp()
        updateSummary()
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

    /** Swap language + file type on the ONE editor field — the field re-runs the settings provider itself,
     *  which re-wires undo and diagnostics. Accepts raw formats like "js" — validation keeps the verbatim
     *  string, the combo displays its family. */
    private fun setFormat(newFormat: String) {
        if (newFormat == scriptFormat) return
        scriptFormat = newFormat
        state.format = newFormat
        val document = LanguageTextField.createDocument(
            field.text, language.ideLanguage(), project, LanguageTextField.SimpleDocumentCreator())
        field.setNewDocumentAndFileType(language.fileType(), document)
        applyContextStamp()   // new document = new PsiFile — re-stamp the completion context
        updateSummary()
        diagnostics.scheduleRevalidate()
    }

    /** `Script task (BPMN) · Groovy` — what the chips below are about. */
    private fun updateSummary() {
        context.setSummary("${scriptContext.display} · ${language.display}")
    }

    /** A chip click drops the picked name into the script at the caret (undoable command). */
    private fun insertAtCaret(name: String) {
        val editor = field.editor ?: return
        val offset = editor.caretModel.offset
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(offset, name)
        }
        editor.caretModel.moveToOffset(offset + name.length)
        IdeFocusManager.getInstance(project).requestFocus(field, true)
    }

    /** Tell the bindings completion which context to offer (see [ScriptScope.CONTEXT_KEY]) and mark
     *  the scratch file for whole-project import resolution. */
    private fun applyContextStamp() {
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(field.document) ?: return
        psiFile.putUserData(ScriptScope.CONTEXT_KEY, scriptContext)
        psiFile.virtualFile?.putUserData(ScriptScope.PLAYGROUND_FILE, true)
    }
}
