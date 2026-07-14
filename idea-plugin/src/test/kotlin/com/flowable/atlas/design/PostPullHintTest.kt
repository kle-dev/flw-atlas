package com.flowable.atlas.design

import com.flowable.atlas.hub.AtlasHubPanel
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Feature 3: the Atlas Hub flags a stale explorer when the last Design pull is newer than the newest
 * generated artifact.
 */
class PostPullHintTest : BasePlatformTestCase() {

    fun testStaleOnlyWhenArtifactOlderThanLastPull() {
        assertTrue("newest artifact older than pull ⇒ stale", AtlasHubPanel.isExplorerStale(listOf(100L, 200L), 300L))
        assertFalse("artifact newer than pull ⇒ fresh", AtlasHubPanel.isExplorerStale(listOf(400L), 300L))
        assertFalse("no artifacts ⇒ not stale", AtlasHubPanel.isExplorerStale(emptyList(), 300L))
        assertFalse("never pulled ⇒ not stale", AtlasHubPanel.isExplorerStale(listOf(100L), null))
    }
}
