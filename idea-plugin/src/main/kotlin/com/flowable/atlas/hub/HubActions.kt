package com.flowable.atlas.hub

import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.action.FlowableActionIds
import com.flowable.atlas.environment.AtlasCatalog
import com.flowable.atlas.environment.EnvironmentLinks
import com.flowable.atlas.explorer.AtlasBrowser
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
 * you (the explorer, the playground, the search), then a `⋮` menu of what is left. Texts *and* icons
 * come from the registered actions — [registered] hands over the same instances the menus use — so no
 * two surfaces can disagree about a name.
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
        // The three destinations, on the toolbar rather than two clicks down in the menu. Each is
        // DumbAware and stays honest on a cold index — the search reads the cached index and asks for a
        // build rather than waiting for one — which had to be checked: a toolbar button is visible the
        // whole time, where a menu entry is only visible while the menu is open.
        registered(
            FlowableActionIds.OPEN_ATLAS_EXPLORER,
            FlowableActionIds.OPEN_EXPRESSION_PLAYGROUND,
            FlowableActionIds.SEARCH_MODELS,
        ).forEach(::add)
        add(more(project))
    }

    /** What the toolbar does not carry: the environments, then maintenance, then managing them. */
    private fun more(project: Project): DefaultActionGroup =
        DefaultActionGroup(message("hub.toolbar.more"), null, AllIcons.Actions.More).apply {
            isPopup = true
            add(OpenEnvironmentGroup(project))
            add(Separator.getInstance())
            registered(
                FlowableActionIds.GENERATE_MODEL_CONSTANTS, FlowableActionIds.REBUILD_MODEL_INDEX,
            ).forEach(::add)
            add(Separator.getInstance())
            registered(FlowableActionIds.MANAGE_ENVIRONMENTS).forEach(::add)
        }

    /**
     * Hands an environment's own pages to the browser — Design, the app, Control, Hub — one separator per
     * environment, one item per address. It follows **no** pointer: the pull and the playground each point
     * at an environment, and a third rule about which one this opens would be one more thing that can
     * silently be wrong — this asks, every time.
     */
    private class OpenEnvironmentGroup(private val project: Project) :
        ActionGroup(message("hub.toolbar.openEnvironment"), true), DumbAware {

        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            val out = ArrayList<AnAction>()
            EnvironmentLinks.grouped(AtlasCatalog.environments(project), AtlasCatalog.connections(project)).forEach { group ->
                out.add(Separator.create(group.environment.name.ifBlank { "unnamed" }))
                group.links.forEach { connection ->
                    out.add(object : AnAction(
                        connection.kind.display, connection.baseUrl,
                        // A padlock, not a prompt: opening a page changes nothing, so PROD is shown rather
                        // than guarded — but it is shown, so nobody clicks it by mistake.
                        if (connection.requiresConfirmation) AllIcons.Nodes.Padlock else null,
                    ), DumbAware {
                        override fun actionPerformed(e: AnActionEvent) = AtlasBrowser.open(connection.baseUrl, project)
                        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                    })
                }
            }
            return out.toTypedArray()
        }

        override fun update(e: AnActionEvent) {
            // Both halves matter: nothing to open, and nowhere to open it — under Remote Dev on a headless
            // host a browse would simply do nothing at all.
            e.presentation.isEnabled = AtlasBrowser.canOpenUrls() &&
                !EnvironmentLinks.isEmpty(AtlasCatalog.environments(project), AtlasCatalog.connections(project))
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }
}
