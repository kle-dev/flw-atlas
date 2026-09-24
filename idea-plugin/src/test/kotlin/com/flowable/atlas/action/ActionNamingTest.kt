package com.flowable.atlas.action

import com.flowable.atlas.AtlasNotifications
import com.flowable.atlas.FlowableAtlasBundle
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.daemon.LineMarkerProviders
import com.intellij.lang.Language
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The plugin's names, held to the rules the descriptor comments state — one name per thing, one verb
 * per kind of opener, one switch per kind of gutter mark.
 */
class ActionNamingTest : BasePlatformTestCase() {

    private fun atlasActionIds(): List<String> =
        ActionManager.getInstance().getActionIdList("Flowable.")
            .filter { ActionManager.getInstance().getAction(it) !is com.intellij.openapi.actionSystem.ActionGroup }

    /**
     * Find Action lists every action by its own text. Two "From Data Object…" entries — one for
     * Liquibase, one for DTOs — were two lookalikes a reader had to open to tell apart.
     */
    fun testNoTwoActionsShareAText() {
        val byText = atlasActionIds().groupBy { ActionManager.getInstance().getAction(it).templateText }
        val clashes = byText.filterValues { it.size > 1 }
        assertEquals("actions whose Find Action text is not unique", emptyMap<String?, List<String>>(), clashes)
    }

    /** In the Generate submenu the menu already says "Generate": the entry does not say it again. */
    fun testTheGenerateSubmenuShowsShortTextsAndFindActionTheWholeOne() {
        for (id in listOf(
            FlowableActionIds.GENERATE_ATLAS_EXPLORER,
            FlowableActionIds.GENERATE_MODEL_CONSTANTS,
            FlowableActionIds.GENERATE_LIQUIBASE_FROM_DATA_OBJECT,
            FlowableActionIds.GENERATE_LIQUIBASE_FROM_APPS,
            FlowableActionIds.GENERATE_DTO_FROM_DATA_OBJECT,
            FlowableActionIds.GENERATE_DTO_FROM_APPS,
        )) {
            val whole = FlowableActionIds.text(id)
            val inMenu = FlowableActionIds.text(id, ActionPlaces.MAIN_MENU)
            assertTrue("$id: '$whole' names what it generates", whole.startsWith("Generate "))
            assertFalse("$id: '$inMenu' repeats the submenu's verb", inMenu.startsWith("Generate"))
            // The Hub's ⋮ renders the same group and must read like the menu. The descriptor spells that
            // place out as a literal; this is what makes the literal the platform's own spelling.
            assertEquals("popup@AtlasHub", ActionPlaces.getPopupPlace("AtlasHub"))
            assertEquals(id, inMenu, FlowableActionIds.text(id, ActionPlaces.getPopupPlace("AtlasHub")))
        }
    }

    /** A Hub section's button can be short because the section's title says what it is about. */
    fun testHubSectionButtonsAreShortButTheToolbarKeepsTheWholeName() {
        assertEquals("Generate…", FlowableActionIds.text(FlowableActionIds.GENERATE_ATLAS_EXPLORER, FlowableActionIds.HUB_SECTION))
        assertEquals("Open", FlowableActionIds.text(FlowableActionIds.OPEN_ATLAS_EXPLORER, FlowableActionIds.HUB_SECTION))
        assertEquals("Open", FlowableActionIds.text(FlowableActionIds.OPEN_ATLAS_PLAYGROUND, FlowableActionIds.HUB_SECTION))
        // The toolbar's place shows tooltips: "Open" alone there would not say what opens.
        assertEquals("Open Atlas Explorer", FlowableActionIds.text(FlowableActionIds.OPEN_ATLAS_EXPLORER, "AtlasHub"))
    }

    fun testEveryOpenerIsOpenAtlasSomething() {
        for (id in listOf(
            FlowableActionIds.OPEN_ATLAS_HUB,
            FlowableActionIds.OPEN_ATLAS_FINDINGS,
            FlowableActionIds.OPEN_ATLAS_EXPLORER,
            FlowableActionIds.OPEN_ATLAS_PLAYGROUND,
        )) {
            assertTrue(FlowableActionIds.text(id), FlowableActionIds.text(id).startsWith("Open Atlas "))
        }
    }

    /** Ellipsis only where more input always follows: Generate Model Constants asks for nothing. */
    fun testModelConstantsCarryNoEllipsis() {
        assertFalse(FlowableActionIds.text(FlowableActionIds.GENERATE_MODEL_CONSTANTS).endsWith("…"))
    }

    /**
     * Every Atlas gutter mark is a descriptor, so Settings → Editor → General → Gutter Icons lists it
     * with a name and an icon — one switch per kind of mark instead of none at all.
     */
    fun testEveryGutterMarkCanBeSwitchedOffByName() {
        val ours = listOf("JAVA", "XML", "JSON").mapNotNull(Language::findLanguageByID)
            .flatMap { LineMarkerProviders.getInstance().allForLanguage(it) }
            .filter { it.javaClass.name.startsWith("com.flowable.atlas.") }
            .distinctBy { it.javaClass }
        assertEquals("the four Atlas marks", 4, ours.size)
        for (provider in ours) {
            assertTrue("${provider.javaClass.simpleName} is a descriptor", provider is LineMarkerProviderDescriptor)
            provider as LineMarkerProviderDescriptor
            assertTrue(provider.javaClass.simpleName, provider.name?.startsWith("Flowable: ") == true)
            assertNotNull(provider.javaClass.simpleName, provider.icon)
        }
    }

    /** Settings → Notifications lists the two groups by names that say which one to silence. */
    fun testNotificationGroupsHaveDisplayNames() {
        // Asking the manager registers the groups from the descriptor, titles included.
        assertNotNull(NotificationGroupManager.getInstance().getNotificationGroup(AtlasNotifications.GROUP_ID))
        assertNotNull(NotificationGroupManager.getInstance().getNotificationGroup(AtlasNotifications.RESULTS_ID))
        assertEquals(
            FlowableAtlasBundle.message("notification.group.attention"),
            NotificationGroup.getGroupTitle(AtlasNotifications.GROUP_ID),
        )
        assertEquals(
            FlowableAtlasBundle.message("notification.group.results"),
            NotificationGroup.getGroupTitle(AtlasNotifications.RESULTS_ID),
        )
    }
}
