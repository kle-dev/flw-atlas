package com.flowable.atlas.hub

import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.action.FlowableActionIds
import com.flowable.atlas.settings.FlowableAtlasConfigurable
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

/**
 * The Hub's toolbar: what acts on the panel (Refresh, Settings), then the three places the plugin takes
 * you (the explorer, the playground, the search), then `⋮`.
 *
 * The `⋮` **is** *Tools → Flowable Atlas*: [MenuMirror] renders that registered group's children rather
 * than a list of its own. Two hand-kept menus over the same actions is how a reader ends up hunting for
 * an entry in the one place it happens not to be — and that had already happened here, the two differing
 * in both contents and order. Now an action added to the descriptor appears in both, in the same place,
 * or in neither.
 *
 * Refresh is the one refresh. It re-gathers the panel *and* marks the Flowable Design lists stale, so
 * the section's own reload button — same icon, four pixels away, different meaning — is gone.
 */
internal object HubActions {

    /** Registered actions by id, in the order asked for; an id the descriptor does not carry is skipped. */
    private fun registered(vararg ids: String): List<AnAction> {
        val am = ActionManager.getInstance()
        return ids.mapNotNull { am.getAction(it) }
    }

    fun toolbar(project: Project, onRefresh: () -> Unit): DefaultActionGroup = DefaultActionGroup().apply {
        add(object : AnAction(
            message("hub.toolbar.refresh"), message("hub.toolbar.refresh.description"), AllIcons.Actions.Refresh,
        ), DumbAware {
            override fun actionPerformed(e: AnActionEvent) = onRefresh()
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        })
        add(object : AnAction(
            message("hub.toolbar.settings"), message("hub.toolbar.settings.description"), AllIcons.General.Settings,
        ), DumbAware {
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, FlowableAtlasConfigurable::class.java)
            }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        })
        add(Separator.getInstance())
        // The three destinations, on the toolbar rather than inside the menu. Each is DumbAware and stays
        // honest on a cold index — the search reads the cached index and asks for a build rather than
        // waiting for one — which had to be checked: a toolbar button is visible the whole time, where a
        // menu entry is only visible while the menu is open.
        registered(
            FlowableActionIds.OPEN_ATLAS_EXPLORER,
            FlowableActionIds.OPEN_ATLAS_PLAYGROUND,
            FlowableActionIds.GO_TO_MODEL,
        ).forEach(::add)
        add(MenuMirror())
    }

    /** The Tools menu under a `⋮` icon: one registered group, shown in two places. */
    private class MenuMirror :
        ActionGroup(message("hub.toolbar.more"), null, AllIcons.Actions.More), DumbAware {

        init {
            isPopup = true
        }

        // The registered group's own children, read as the descriptor declares them: ActionGroup.getChildren
        // is override-only, and calling it on another group is what the Plugin Verifier flagged.
        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            val menu = ActionManager.getInstance().getAction(FlowableActionIds.MENU) as? DefaultActionGroup
            return menu?.childActionsOrStubs ?: EMPTY_ARRAY
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }
}
