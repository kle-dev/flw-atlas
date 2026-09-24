package com.flowable.atlas.expr.toolwindow

import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.ActionManager
import com.flowable.atlas.action.FlowableActionIds
import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.settings.ExpressionsConfigurable
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import javax.swing.JComponent

/**
 * Toolbar actions of the Atlas Playground. All read only cheap panel/Swing state, hence
 * [ActionUpdateThread.EDT]. Two [DialectToggleAction]s are the mode switch — toggle buttons in a tool
 * window toolbar are the platform's own idiom for it; the scope picker is a [ComboBoxAction] whose items
 * the panel pre-loads off the EDT — [ComboBoxAction.update] must never compute them.
 */

internal class DialectToggleAction(
    private val panel: FlowableExpressionPanel,
    private val dialect: ExpressionDialect,
) : ToggleAction(), DumbAware {

    init {
        templatePresentation.text = message(if (dialect == ExpressionDialect.BACKEND) "playground.backend" else "playground.frontend")
        // Frontend gets the form icon, not a globe: next to a server glyph a globe reads as "web vs.
        // server", which is not the distinction — a form is what a frontend expression sits in.
        templatePresentation.icon =
            if (dialect == ExpressionDialect.BACKEND) AllIcons.Webreferences.Server else AllIcons.FileTypes.UiForm
        templatePresentation.description = message("playground.dialect.description", dialect.display)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun isSelected(e: AnActionEvent): Boolean = panel.dialect == dialect
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (state) panel.switchDialect(dialect)
    }
}

internal class ScopeComboBoxAction(private val panel: FlowableExpressionPanel) : ComboBoxAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.setText(panel.currentScopeLabel(), false)
        e.presentation.description = message("playground.scope.description")
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val group = DefaultActionGroup()
        for (item in panel.scopeItemsSnapshot()) {
            group.add(object : AnAction(), DumbAware {
                init { templatePresentation.setText(item.label, false) }
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) = panel.selectScope(item)
            })
        }
        return group
    }
}

/**
 * Evaluates against Flowable Work. Always on the toolbar — disabled in the frontend dialect, saying why —
 * so switching the dialect does not move the buttons beside it; the two dialect-bound actions used to
 * appear and vanish, and the toolbar changed shape under the pointer.
 */
internal class EvaluateAgainstAppAction(private val panel: FlowableExpressionPanel) : AnAction(
    message("playground.evaluate"), null, AllIcons.Actions.Execute,
), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val backend = panel.dialect == ExpressionDialect.BACKEND
        e.presentation.isEnabled = backend && panel.canEvaluateAgainstApp && !panel.isEvaluating
        e.presentation.description =
            if (backend) message("playground.evaluate.description", evaluateShortcutText())
            else message("playground.evaluate.backendOnly")
    }

    override fun actionPerformed(e: AnActionEvent) = panel.evaluateAgainstApp()
}

/** Frontend only, and — like Evaluate — disabled rather than hidden in the other dialect. */
internal class ShowSubEvaluationsToggle(private val panel: FlowableExpressionPanel) : ToggleAction(
    message("playground.subValues"), null, AllIcons.General.InspectionsEye,
), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        super.update(e)
        val frontend = panel.dialect == ExpressionDialect.FRONTEND
        e.presentation.isEnabled = frontend
        e.presentation.description = message(if (frontend) "playground.subValues.description" else "playground.subValues.frontendOnly")
    }

    override fun isSelected(e: AnActionEvent): Boolean = panel.showSubEvaluations
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        panel.showSubEvaluations = state
    }
}

/** Editor over context and result, or beside them — remembered per tab (see `PlaygroundShell`). */
internal class StackPanelsToggle(private val isStacked: () -> Boolean, private val setStacked: (Boolean) -> Unit) : ToggleAction(
    message("playground.stack"),
    message("playground.stack.description"),
    AllIcons.Actions.SplitHorizontally,
), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun isSelected(e: AnActionEvent): Boolean = isStacked()
    override fun setSelected(e: AnActionEvent, state: Boolean) = setStacked(state)
}

/**
 * The gear: layout, then the settings a playground reader reaches for. The Expressions tab gets the
 * expression settings and *Manage Environments…* — the registered action, so it has the name it has
 * everywhere else (it was *Environment Settings…* here and *Manage Environments…* one row below). The
 * Scripts tab has no environment, so it gets neither.
 */
internal class PlaygroundSettingsGroup(
    project: com.intellij.openapi.project.Project,
    stackPanels: AnAction,
    expressionSettings: Boolean = true,
) : DefaultActionGroup(), DumbAware {

    init {
        templatePresentation.text = message("playground.settings")
        templatePresentation.icon = AllIcons.General.Settings
        isPopup = true
        add(stackPanels)
        if (expressionSettings) {
            addSeparator()
            add(object : AnAction(message("playground.settings.expressions")), DumbAware {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) =
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, ExpressionsConfigurable::class.java)
            })
            ActionManager.getInstance().getAction(FlowableActionIds.MANAGE_ENVIRONMENTS)?.let(::add)
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/** Ctrl+Enter as this keymap spells it — ⌘⏎ on a Mac — instead of a literal that was wrong on half the machines. */
internal fun evaluateShortcutText(): String =
    KeymapUtil.getFirstKeyboardShortcutText(CommonShortcuts.getCtrlEnter()).ifEmpty { "Ctrl+Enter" }
