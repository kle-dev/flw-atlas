package com.flowable.atlas.environment

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import java.awt.Component

/**
 * The environment switcher behind the Tools-menu actions and the pull's "nothing chosen yet" case.
 *
 * **It opens instantly.** Every item comes from the in-memory catalog, so there is no spinner and no
 * disabled control while something is fetched — the *consequences* of a pick load asynchronously, the
 * pick itself never waits.
 *
 * **Everything in it selects.** Only environments that actually have a server of the requested kind
 * are listed: picking one has to *be* the switch, not a detour into Settings to first create what the
 * entry promised. An environment missing from the list is one with no server of that kind, and the
 * trailing *Manage environments…* is where that gets fixed.
 */
object EnvironmentPopup {

    /**
     * Shows the switcher for [kind], centred — its callers are the Tools-menu actions, which have no
     * control to hang it off. (The Atlas Hub and the playground use ordinary combo boxes instead: they
     * have a row to put one in, and a closed combo says "this is yours to change" without a click.)
     * [onChosen] runs after the selection is stored.
     */
    fun showCentered(project: Project, kind: ConnectionKind, anchor: Component, onChosen: (AtlasConnection) -> Unit = {}) =
        build(project, kind, anchor, onChosen).showCenteredInCurrentWindow(project)

    private fun build(
        project: Project,
        kind: ConnectionKind,
        near: Component,
        onChosen: (AtlasConnection) -> Unit,
    ): com.intellij.openapi.ui.popup.ListPopup {
        val available = AtlasCatalog.connections(project, kind)
        val currentId = AtlasConnectionSelection.selected(project, kind)?.id
        val group = DefaultActionGroup()

        // In catalog order, which is the user's order — the DEV → QA → UAT → PROD pipeline.
        available.forEach { connection ->
            group.add(item(connection, connection.id == currentId) {
                AtlasConnectionSelection.select(project, kind, connection.id)
                onChosen(connection)
            })
        }
        if (available.isNotEmpty()) group.addSeparator()
        group.add(command("Manage environments…") { openSettings(project, null) })

        return JBPopupFactory.getInstance()
            .createActionGroupPopup(
                "Select ${kind.display} Environment",
                group,
                DataManager.getInstance().getDataContext(near),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false,
            )
    }

    private fun item(connection: AtlasConnection, selected: Boolean, choose: () -> Unit): AnAction =
        object : AnAction(connection.environmentName, connection.baseUrl, null), Toggleable {
            override fun actionPerformed(e: AnActionEvent) = choose()

            override fun update(e: AnActionEvent) {
                // A lock rather than a warning: nothing is wrong with PROD, it is simply guarded.
                e.presentation.icon = if (connection.requiresConfirmation) AllIcons.Nodes.Padlock else null
                Toggleable.setSelected(e.presentation, selected)
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

    private fun command(text: String, run: () -> Unit): AnAction =
        object : AnAction(text) {
            override fun actionPerformed(e: AnActionEvent) = run()

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

    private fun openSettings(project: Project, environmentId: String?) {
        ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, com.flowable.atlas.settings.EnvironmentsConfigurable::class.java)
    }
}
