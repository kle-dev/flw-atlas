package com.flowable.atlas.action

import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.environment.AtlasCatalog
import com.flowable.atlas.environment.EnvironmentLinks
import com.flowable.atlas.explorer.AtlasBrowser
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

/**
 * Hands an environment's own pages to the browser — Design, the app, Control, Hub — one separator per
 * environment, one item per address. It follows **no** pointer: the pull and the playground each point
 * at an environment, and a third rule about which one this opens would be one more thing that can
 * silently be wrong — this asks, every time.
 *
 * Registered in `plugin.xml` rather than built in the Hub, because the Hub's `⋮` *is* the Tools menu:
 * an entry that lived only in one of them is what made the two menus disagree.
 */
class OpenEnvironmentInBrowserGroup :
    ActionGroup(message("hub.toolbar.openEnvironment"), true), DumbAware {

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return EMPTY_ARRAY
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
        val project: Project? = e.project
        // Both halves matter: nothing to open, and nowhere to open it — under Remote Dev on a headless
        // host a browse would simply do nothing at all.
        e.presentation.isEnabled = project != null && AtlasBrowser.canOpenUrls() &&
            !EnvironmentLinks.isEmpty(AtlasCatalog.environments(project), AtlasCatalog.connections(project))
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
