package com.flowable.atlas.hub

import com.flowable.atlas.action.FlowableActionIds
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The Hub toolbar's shape.
 *
 * Three actions moved out of the `⋮` menu onto the toolbar itself, which changes two things a menu never
 * had to answer for. A toolbar is a row of icons, so an action that arrives without one draws as a text
 * button in a stripe that has no room for it; and a toolbar button is visible the whole time, where a
 * menu entry is only visible while the menu is open, so a button that misbehaves on a cold index
 * misbehaves in plain sight. Both are asserted here.
 */
class HubActionsTest : BasePlatformTestCase() {

    private fun toolbar(): List<AnAction> = HubActions.toolbar(project) {}.childActionsOrStubs.toList()

    private fun overflow(): DefaultActionGroup = toolbar().filterIsInstance<DefaultActionGroup>().single()

    private fun idOf(action: AnAction): String? = ActionManager.getInstance().getId(action)

    fun testTheToolbarReadsPanelVerbsThenDestinationsThenOverflow() {
        val children = toolbar()
        // Refresh and Settings are anonymous — they act on this panel and are registered nowhere else.
        assertNull(idOf(children[0]))
        assertNull(idOf(children[1]))
        assertTrue("a separator divides the panel's own verbs from the destinations", children[2] is Separator)
        assertEquals(
            listOf(
                FlowableActionIds.OPEN_ATLAS_EXPLORER,
                FlowableActionIds.OPEN_EXPRESSION_PLAYGROUND,
                FlowableActionIds.SEARCH_MODELS,
            ),
            children.subList(3, 6).map { idOf(it) },
        )
        assertTrue("the ⋮ group is last", children.last() is DefaultActionGroup)
        assertEquals(7, children.size)
    }

    fun testEveryToolbarButtonHasAnIcon() {
        val nameless = toolbar()
            .filter { it !is Separator }
            .filter { it.templatePresentation.icon == null }
            .map { it.templatePresentation.text ?: it.javaClass.name }
        assertEquals("a toolbar button with no icon draws as text in the stripe", emptyList<String>(), nameless)
    }

    fun testTheThreeDestinationsLeftTheOverflowMenu() {
        val ids = overflow().childActionsOrStubs.mapNotNull { idOf(it) }
        assertFalse(ids.contains(FlowableActionIds.OPEN_ATLAS_EXPLORER))
        assertFalse(ids.contains(FlowableActionIds.OPEN_EXPRESSION_PLAYGROUND))
        assertFalse(ids.contains(FlowableActionIds.SEARCH_MODELS))
    }

    fun testTheOverflowMenuKeepsWhatTheToolbarDoesNotCarry() {
        val children = overflow().childActionsOrStubs
        val ids = children.mapNotNull { idOf(it) }
        assertTrue("maintenance stays in the menu", ids.containsAll(
            listOf(
                FlowableActionIds.GENERATE_MODEL_CONSTANTS,
                FlowableActionIds.REBUILD_MODEL_INDEX,
                FlowableActionIds.MANAGE_ENVIRONMENTS,
            ),
        ))
        // The environments group is anonymous like the panel verbs, so it is counted by its type.
        assertEquals(1, children.count { it !is DefaultActionGroup && it !is Separator && idOf(it) == null })
    }

    fun testTheThreeDestinationsAreDumbAware() {
        val am = ActionManager.getInstance()
        listOf(
            FlowableActionIds.OPEN_ATLAS_EXPLORER,
            FlowableActionIds.OPEN_EXPRESSION_PLAYGROUND,
            FlowableActionIds.SEARCH_MODELS,
        ).forEach { id ->
            assertTrue("$id is on the toolbar while the index builds, so it must be DumbAware",
                am.getAction(id) is DumbAware)
        }
    }
}
