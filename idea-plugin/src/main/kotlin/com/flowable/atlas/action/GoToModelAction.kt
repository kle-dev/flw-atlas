package com.flowable.atlas.action

import com.flowable.atlas.AtlasNotifications
import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.navigation.se.FlowableModelSeContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
import com.intellij.idea.AppMode
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware

/**
 * Tools → Flowable Atlas → *Go to Model…*: opens Search Everywhere with the **Flowable Model** tab
 * already selected — one jump, and the popup closes behind you. Its sibling [FindInModelsAction] leaves
 * a list of every hit instead; the names follow the platform's own *Go to File* / *Find in Files* pair,
 * because "search" and "find" side by side said nothing about which was which.
 *
 * The tab is otherwise only reachable by pressing Shift twice and tabbing across to it, which is not
 * something anyone discovers on their own — so the plugin's own surfaces (this menu entry, the Project
 * view's context menu, and the model count in the Atlas Hub's header, which is a link here) point at it.
 *
 * ## Under Remote Development it opens the result list instead
 * The Search Everywhere popup renders in the thin client, and a tab contributed by a plugin running on
 * the host does not reach it: on a remote IDE the **Flowable Model** tab is simply not in that popup, so
 * there is nothing for this action to select and the button appeared to do nothing at all.
 *
 * Routing the action to the client does not help either — that was the first attempt, and it is worse:
 * the plugin is not loaded there, so the action has no implementation to run. What does work is the
 * other half of the same search: a modal dialog and the Find tool window are ordinary UI that Remote
 * Development mirrors, so the host asks for a pattern and streams the hits into a list. Fewer keystrokes
 * than the popup, the same index, the same model text — and it happens rather than not happening.
 *
 * Making the tab itself work remotely means republishing the contributor through the platform's newer
 * `searchEverywhere.itemsProviderFactory`, which is a different piece of work.
 */
class GoToModelAction : AnAction(), DumbAware {

    private val log = logger<GoToModelAction>()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (AppMode.isRemoteDevHost()) {
            FindInModelsAction.ask(e, message("goToModel.remote.title"))
            return
        }
        val manager = SearchEverywhereManager.getInstance(project)
        val opened = runCatching {
            if (manager.isShown) manager.selectedTabID = FlowableModelSeContributor.ID
            else manager.show(FlowableModelSeContributor.ID, "", e)
        }.onFailure { log.debug("Flowable models Search-Everywhere tab unavailable — falling back", it) }
            .isSuccess
        if (!opened) {
            // The tab is contributed by an extension point, so it only exists once the plugin is fully
            // loaded — same restart caveat as the tool window.
            AtlasNotifications.info(
                project,
                "The Flowable Model search tab isn't registered yet. If you just installed or " +
                    "updated the plugin, restart the IDE and try again.",
            )
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
