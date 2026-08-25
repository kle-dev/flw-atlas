package com.flowable.atlas.script.toolwindow

import com.flowable.atlas.script.ScriptContext
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import javax.swing.JComponent

/**
 * Toolbar actions of the Script Playground — same rules as the expression playground's actions:
 * everything reads only cheap panel state, hence [ActionUpdateThread.EDT]; the combo's `update`
 * never computes anything.
 */

internal class ScriptLanguageComboBoxAction(private val panel: FlowableScriptPanel) : ComboBoxAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.setText(panel.language.display, false)
        e.presentation.description = "Script language (the scriptFormat the validation runs under)"
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val group = DefaultActionGroup()
        for (lang in PlaygroundScriptLanguage.entries) {
            group.add(object : AnAction(), DumbAware {
                init { templatePresentation.setText(lang.display, false) }
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) = panel.switchLanguage(lang)
            })
        }
        return group
    }
}

internal class ScriptContextComboBoxAction(private val panel: FlowableScriptPanel) : ComboBoxAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.setText(panel.scriptContext.display, false)
        e.presentation.description =
            "Script context — decides which root objects (execution, task, planItemInstance, flw, …) validation knows"
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val group = DefaultActionGroup()
        for (ctx in ScriptContext.entries.filter { it != ScriptContext.UNKNOWN }) {
            group.add(object : AnAction(), DumbAware {
                init { templatePresentation.setText(ctx.display, false) }
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) = panel.switchContext(ctx)
            })
        }
        return group
    }
}

internal class LoadScriptFromModelAction(private val panel: FlowableScriptPanel) : AnAction(
    "Load Script from Model…",
    "Pick a script from the project's models — script tasks, listener scripts, action bots",
    AllIcons.Actions.MenuOpen,
), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) = ScriptPicker.show(panel)
}

internal class LoadExampleScriptAction(private val panel: FlowableScriptPanel) : AnAction(
    "Load Example…",
    "Load a worked Flowable script example — one per context and language, ready to edit",
    AllIcons.Nodes.Template,
), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) = ScriptExamples.show(panel)
}
