package com.flowable.atlas.action

import com.flowable.atlas.navigation.se.FlowableModelSeContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.Messages

/**
 * Tools → Flowable Atlas → "Search Models…": opens Search Everywhere with the **Flowable Model** tab
 * already selected.
 *
 * The tab is otherwise only reachable by pressing Shift twice and tabbing across to it, which is not
 * something anyone discovers on their own — so the plugin's own surfaces (this menu entry and the
 * Atlas Hub's *Model Index* row) point at it.
 */
class SearchFlowableModelsAction : AnAction(), DumbAware {

    private val LOG = logger<SearchFlowableModelsAction>()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = SearchEverywhereManager.getInstance(project)
        val opened = runCatching {
            if (manager.isShown) manager.selectedTabID = FlowableModelSeContributor.ID
            else manager.show(FlowableModelSeContributor.ID, "", e)
        }.onFailure { LOG.debug("Flowable models Search-Everywhere tab unavailable — falling back", it) }
            .isSuccess
        if (!opened) {
            // The tab is contributed by an extension point, so it only exists once the plugin is
            // fully loaded — same restart caveat as the tool window.
            Messages.showInfoMessage(
                project,
                "The Flowable Model search tab isn't registered yet. If you just installed or " +
                    "updated the plugin, restart the IDE and try again.",
                "Flowable Atlas",
            )
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
