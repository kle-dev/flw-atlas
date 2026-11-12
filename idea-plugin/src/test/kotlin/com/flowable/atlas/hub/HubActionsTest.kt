package com.flowable.atlas.hub

import com.flowable.atlas.action.FlowableActionIds
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The Hub toolbar's shape, and the one property that keeps it honest: the `⋮` is *Tools → Flowable
 * Atlas*, not a second list of the same actions.
 *
 * Both halves are here because both were wrong before. Three actions moved out of the menu onto the
 * toolbar, which a menu never had to answer for — a toolbar is a row of icons, so one arriving without
 * an icon draws as a text button in a stripe with no room for it, and a toolbar button is visible the
 * whole time, where a menu entry is only visible while the menu is open. And the two menus over the same
 * actions had drifted into different contents *and* a different order, which is a navigation people have
 * to learn twice.
 */
class HubActionsTest : BasePlatformTestCase() {

    private fun toolbar(): List<AnAction> = HubActions.toolbar(project) {}.childActionsOrStubs.toList()

    private fun overflow(): ActionGroup = toolbar().last() as ActionGroup

    private fun childrenOf(group: ActionGroup): List<AnAction> =
        group.getChildren(TestActionEvent.createTestEvent()).toList()

    private fun idOf(action: AnAction): String? = ActionManager.getInstance().getId(action)

    fun testTheToolbarReadsPanelVerbsThenDestinationsThenTheMenu() {
        val children = toolbar()
        // Refresh and Settings are anonymous — they act on this panel and are registered nowhere else.
        assertNull(idOf(children[0]))
        assertNull(idOf(children[1]))
        assertTrue("a separator divides the panel's own verbs from the destinations", children[2] is Separator)
        assertEquals(
            listOf(
                FlowableActionIds.OPEN_ATLAS_EXPLORER,
                FlowableActionIds.OPEN_ATLAS_PLAYGROUND,
                FlowableActionIds.GO_TO_MODEL,
            ),
            children.subList(3, 6).map { idOf(it) },
        )
        assertTrue("the ⋮ group is last", children.last() is ActionGroup)
        assertEquals(7, children.size)
    }

    fun testEveryToolbarButtonHasAnIcon() {
        val nameless = toolbar()
            .filter { it !is Separator }
            .filter { it.templatePresentation.icon == null }
            .map { it.templatePresentation.text ?: it.javaClass.name }
        assertEquals("a toolbar button with no icon draws as text in the stripe", emptyList<String>(), nameless)
    }

    fun testTheOverflowMenuIsTheToolsMenuItself() {
        val menu = ActionManager.getInstance().getAction(FlowableActionIds.MENU) as ActionGroup
        val fromMenu = childrenOf(menu).map { idOf(it) ?: it.javaClass.name }
        val fromHub = childrenOf(overflow()).map { idOf(it) ?: it.javaClass.name }
        assertEquals("the Hub's ⋮ renders the Tools menu, so the two cannot drift apart", fromMenu, fromHub)
        assertTrue("and it is not empty — a mirror of nothing would pass the line above too", fromHub.isNotEmpty())
    }

    /**
     * The ⋮ hands the popup *actions*, not stubs. A stub has an id — so the test above passed — but it is
     * never updated: 0.27.0 drew the environments group as an empty row and showed Copy Model Key and
     * Compare in a panel that has no key or model to act on.
     */
    fun testTheOverflowMenuHoldsResolvedActionsThatUpdate() {
        val children = childrenOf(overflow()).filter { it !is Separator }
        val stubs = children.filter { it is com.intellij.openapi.actionSystem.ActionStubBase }.map { idOf(it) }
        assertEquals("unresolved stubs in the Hub's ⋮", emptyList<String?>(), stubs)
        val environments = children.single { idOf(it) == "Flowable.OpenEnvironmentInBrowser" }
        assertFalse("the environments group has a name", environments.templateText.isNullOrBlank())

        // In the Hub there is no editor and no selected file: the context actions hide themselves.
        val context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project)
        for (id in listOf(FlowableActionIds.COPY_MODEL_KEY, FlowableActionIds.COMPARE_MODEL_WITH_ARCHIVE)) {
            val action = children.single { idOf(it) == id }
            val event = TestActionEvent.createTestEvent(action, context)
            action.update(event)
            assertFalse("$id is hidden where there is nothing to act on", event.presentation.isEnabledAndVisible)
        }
    }

    fun testBothSearchesAreReachableFromTheMenu() {
        // The pair a reader has to tell apart: one opens the popup, the other the result list. Neither
        // may be the one that is only in the other menu.
        val ids = childrenOf(overflow()).mapNotNull { idOf(it) }
        assertTrue(ids.contains(FlowableActionIds.GO_TO_MODEL))
        assertTrue(ids.contains(FlowableActionIds.FIND_IN_MODELS))
    }

    fun testTheTwoSearchesDoNotShareAnIcon() {
        // They sit next to each other in the menu and differ by one word. Actions.Search and Actions.Find
        // are near-identical magnifiers, which left the wording to carry the whole distinction.
        val am = ActionManager.getInstance()
        val goTo = am.getAction(FlowableActionIds.GO_TO_MODEL).templatePresentation.icon
        val find = am.getAction(FlowableActionIds.FIND_IN_MODELS).templatePresentation.icon
        assertNotNull(goTo)
        assertNotNull(find)
        assertFalse("the pair has to be told apart without reading", goTo == find)
    }

    fun testGoToModelRunsOnTheHostRatherThanTheThinClient() {
        // It was briefly built on SearchEverywhereBaseAction, which is marked
        // ActionRemoteBehaviorSpecification.Frontend — that routes an action to the thin client, where
        // this plugin is not loaded at all, so under Remote Development it ran nowhere. The action has
        // to stay on the host: only there can it see that it is remote and open the result list instead.
        val action = ActionManager.getInstance().getAction(FlowableActionIds.GO_TO_MODEL)
        val interfaces = generateSequence(action.javaClass as Class<*>) { it.superclass }
            .flatMap { it.interfaces.asSequence() }
            .flatMap { generateSequence(it) { p -> p.interfaces.firstOrNull() } }
            .map { it.name }
            .toSet()
        assertFalse(
            "Go to Model must run on the host: $interfaces",
            interfaces.any { it.endsWith("ActionRemoteBehaviorSpecification\$Frontend") },
        )
    }

    fun testTheThreeDestinationsAreDumbAware() {
        val am = ActionManager.getInstance()
        listOf(
            FlowableActionIds.OPEN_ATLAS_EXPLORER,
            FlowableActionIds.OPEN_ATLAS_PLAYGROUND,
            FlowableActionIds.GO_TO_MODEL,
        ).forEach { id ->
            assertTrue("$id is on the toolbar while the index builds, so it must be DumbAware",
                am.getAction(id) is DumbAware)
        }
    }
}
