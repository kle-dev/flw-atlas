package com.flowable.atlas.expr.toolwindow

import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.settings.EnvironmentsConfigurable
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
 * Toolbar actions of the Expression Playground. All read only cheap panel/Swing state, hence
 * [ActionUpdateThread.EDT]. Two [DialectToggleAction]s are the mode switch — toggle buttons in a tool
 * window toolbar are the platform's own idiom for it; the scope picker is a [ComboBoxAction] whose items
 * the panel pre-loads off the EDT — [ComboBoxAction.update] must never compute them.
 */

internal class DialectToggleAction(
    private val panel: FlowableExpressionPanel,
    private val dialect: ExpressionDialect,
) : ToggleAction(), DumbAware {

    init {
        templatePresentation.text = if (dialect == ExpressionDialect.BACKEND) "Backend" else "Frontend"
        // Frontend gets the form icon, not a globe: next to a server glyph a globe reads as "web vs.
        // server", which is not the distinction — a form is what a frontend expression sits in.
        templatePresentation.icon =
            if (dialect == ExpressionDialect.BACKEND) AllIcons.Webreferences.Server else AllIcons.FileTypes.UiForm
        templatePresentation.description = "Validate and evaluate as ${dialect.display}"
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
        e.presentation.description = "Scope variable/field completion to one model"
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

internal class EvaluateAgainstAppAction(private val panel: FlowableExpressionPanel) : AnAction(
    "Evaluate Against App",
    "Evaluate the expression against the running app via the Flowable Inspect REST API (Ctrl+Enter)",
    AllIcons.Actions.Execute,
), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = panel.dialect == ExpressionDialect.BACKEND
        e.presentation.isEnabled = panel.canEvaluateAgainstApp && !panel.isEvaluating
    }

    override fun actionPerformed(e: AnActionEvent) = panel.evaluateAgainstApp()
}

internal class ShowSubEvaluationsToggle(private val panel: FlowableExpressionPanel) : ToggleAction(
    "Show Sub-Expression Values",
    "Show what each sub-expression evaluates to, inline after it",
    AllIcons.General.InspectionsEye,
), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isVisible = panel.dialect == ExpressionDialect.FRONTEND
    }

    override fun isSelected(e: AnActionEvent): Boolean = panel.showSubEvaluations
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        panel.showSubEvaluations = state
    }
}

/** Editor over context and result, or beside them — remembered per tab (see `PlaygroundShell`). */
internal class StackPanelsToggle(private val isStacked: () -> Boolean, private val setStacked: (Boolean) -> Unit) : ToggleAction(
    "Stack Panels",
    "Put the context and result panels under the editor instead of beside it",
    AllIcons.Actions.SplitHorizontally,
), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun isSelected(e: AnActionEvent): Boolean = isStacked()
    override fun setSelected(e: AnActionEvent, state: Boolean) = setStacked(state)
}

/** The gear: layout, then the two settings pages a playground reader reaches for. */
internal class PlaygroundSettingsGroup(
    project: com.intellij.openapi.project.Project,
    stackPanels: AnAction,
    expressionSettings: Boolean = true,
) : DefaultActionGroup(), DumbAware {

    init {
        templatePresentation.text = "Settings"
        templatePresentation.icon = AllIcons.General.Settings
        isPopup = true
        add(stackPanels)
        addSeparator()
        if (expressionSettings) {
            add(object : AnAction("Expression Settings…"), DumbAware {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) =
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, ExpressionsConfigurable::class.java)
            })
        }
        add(object : AnAction("Environment Settings…"), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) =
                ShowSettingsUtil.getInstance().showSettingsDialog(project, EnvironmentsConfigurable::class.java)
        })
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
