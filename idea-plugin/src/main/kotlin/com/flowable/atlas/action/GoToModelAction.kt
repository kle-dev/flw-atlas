package com.flowable.atlas.action

import com.flowable.atlas.navigation.se.FlowableModelSeContributor
import com.intellij.ide.actions.SearchEverywhereBaseAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Tools → Flowable Atlas → *Go to Model…*: opens Search Everywhere with the **Flowable Model** tab
 * already selected — one jump, and the popup closes behind you. Its sibling
 * [FindInModelsAction] leaves a list of every hit instead; the names follow the platform's own *Go to
 * File* / *Find in Files* pair, because "search" and "find" side by side said nothing about which was
 * which.
 *
 * The tab is otherwise only reachable by pressing Shift twice and tabbing across to it, which is not
 * something anyone discovers on their own — so the plugin's own surfaces (this menu entry, the Project
 * view's context menu, and the model count in the Atlas Hub's header, which is a link here) point at it.
 *
 * ## Why it extends the platform's base action
 * Calling `SearchEverywhereManager.show(…)` from a plain `AnAction` works locally and does **nothing at
 * all under Remote Development**: the popup is a frontend component, an ordinary action runs on the
 * backend, and the call reaches no UI. [SearchEverywhereBaseAction] implements
 * `ActionRemoteBehaviorSpecification.Frontend`, which is what routes an action to the thin client — it
 * is how every *Go to Class / File / Symbol* action is built, and the only supported way to open this
 * popup on somebody else's screen.
 */
class GoToModelAction : SearchEverywhereBaseAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        // useEditorSelection: the word under the caret prefills the field, as it does for Go to Symbol.
        showInSearchEverywherePopup(FlowableModelSeContributor.ID, e, true, true)
    }
}
