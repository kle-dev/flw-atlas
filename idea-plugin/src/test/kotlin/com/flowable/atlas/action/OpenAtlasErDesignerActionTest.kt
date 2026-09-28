package com.flowable.atlas.action

import com.flowable.atlas.explorer.AtlasArtifact
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The designer's entry — in the Tools menu, the Hub's ⋮ and the Hub's Explorer block — is there only for a
 * project that generates the ER diagram page: an entry for something the team never chose is menu noise.
 */
class OpenAtlasErDesignerActionTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            FlowableAtlasProjectSettings.getInstance(project).atlasArtifacts = mutableSetOf(AtlasArtifact.EXPLORER_HTML)
        } finally {
            super.tearDown()
        }
    }

    private fun visible(): Boolean {
        val action = ActionManager.getInstance().getAction(FlowableActionIds.OPEN_ATLAS_ER_DESIGNER)
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))
        action.update(event)
        return event.presentation.isEnabledAndVisible
    }

    fun testOfferedOnlyWhenTheProjectGeneratesThePage() {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        assertFalse("not chosen: no entry", visible())
        settings.atlasArtifacts = mutableSetOf(AtlasArtifact.EXPLORER_HTML, AtlasArtifact.ERD_HTML)
        assertTrue("chosen: the entry opens it", visible())
        assertEquals("the Hub's button says what it opens, in the Explorer block's words", "ER diagram",
            FlowableActionIds.text(FlowableActionIds.OPEN_ATLAS_ER_DESIGNER, FlowableActionIds.HUB_SECTION))
    }
}
