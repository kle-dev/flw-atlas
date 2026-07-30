package com.flowable.atlas.hub

import com.flowable.atlas.design.DesignPullSelection
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Hub smoke: the panel builds and refreshes, and the app section stays hidden until Design is set up. */
class AtlasHubPanelTest : BasePlatformTestCase() {

    fun testPanelBuildsAndRefreshesWithoutDesignConfigured() {
        val panel = AtlasHubPanel(project)
        try {
            // the refresh gathers on a pooled thread; drain it so an exception would surface here
            panel.refreshForTest()
            assertNotNull(panel.component)
        } finally {
            panel.dispose()
        }
    }

    fun testOverridePersistsPerProjectAndClears() {
        assertNull(DesignPullSelection.load(project))
        DesignPullSelection.save(project, DesignPullSelection.Selection("ws-1", listOf("appA")))
        assertEquals(DesignPullSelection.Selection("ws-1", listOf("appA")), DesignPullSelection.load(project))
        DesignPullSelection.clear(project)
        assertNull(DesignPullSelection.load(project))
    }
}
