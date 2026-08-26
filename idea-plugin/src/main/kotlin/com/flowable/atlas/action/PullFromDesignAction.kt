package com.flowable.atlas.action

import com.flowable.atlas.design.DesignPullService
import com.flowable.atlas.environment.AtlasConnectionSelection
import com.flowable.atlas.environment.AtlasCatalog
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.environment.EnvironmentPopup
import com.flowable.atlas.settings.EnvironmentsConfigurable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.wm.WindowManager

/**
 * Tools → Flowable Atlas → "Pull from Flowable Design": downloads the selected environment's apps into
 * the project and rebuilds the model index.
 *
 * It does **not** ask which environment every time. That was the tempting reading of "give the pull a
 * dropdown", and it is the wrong one: it turns the common case — pull from the environment I am
 * already working against — into a decision on every single invocation. The switcher lives in the
 * Atlas Hub, one row above the pull link, and that link names its target (*Pull from QA*), so the
 * answer is visible without opening anything.
 *
 * The dropdown does appear where it is the only useful thing to show: when nothing is chosen yet and
 * there is something to choose from. A missing workspace or app is reported by the pull itself, which
 * beats reopening Settings and keeps the two entry points from disagreeing.
 */
class PullFromDesignAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (AtlasConnectionSelection.selected(project, ConnectionKind.DESIGN) != null) {
            DesignPullService.getInstance(project).pullInBackground()
            return
        }
        // Nothing chosen. With connections to choose from, a popup is far cheaper than a settings
        // dialog; with none, the settings page is the only thing that helps.
        val available = AtlasCatalog.connections(project, ConnectionKind.DESIGN)
        if (available.isEmpty()) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, EnvironmentsConfigurable::class.java)
            if (AtlasConnectionSelection.selected(project, ConnectionKind.DESIGN) != null) {
                DesignPullService.getInstance(project).pullInBackground()
            }
            return
        }
        val anchor = WindowManager.getInstance().getFrame(project)?.rootPane ?: return
        EnvironmentPopup.showCentered(project, ConnectionKind.DESIGN, anchor) {
            DesignPullService.getInstance(project).pullInBackground()
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }
}
