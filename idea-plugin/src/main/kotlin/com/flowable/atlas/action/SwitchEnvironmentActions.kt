package com.flowable.atlas.action

import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.environment.EnvironmentPopup
import com.flowable.atlas.settings.EnvironmentsConfigurable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.wm.WindowManager

/**
 * The menu-and-keyboard form of the environment switcher. The Atlas Hub has the same popup on a link,
 * but the Hub is a tool window someone may not have open — and *Find Action* is how a lot of this
 * plugin's users reach everything else, so the switch belongs there too.
 */
abstract class SwitchEnvironmentAction(private val kind: ConnectionKind) : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val anchor = WindowManager.getInstance().getFrame(project)?.rootPane ?: return
        EnvironmentPopup.showCentered(project, kind, anchor)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

class SwitchDesignEnvironmentAction : SwitchEnvironmentAction(ConnectionKind.DESIGN)

class SwitchWorkEnvironmentAction : SwitchEnvironmentAction(ConnectionKind.WORK)

/** Opens the environment editor — replaces the old "Configure Design Connection…". */
class ManageEnvironmentsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, EnvironmentsConfigurable::class.java)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
