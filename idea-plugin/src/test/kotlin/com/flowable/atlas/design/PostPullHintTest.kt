package com.flowable.atlas.design

import com.flowable.atlas.explorer.AtlasExplorerStaleness
import com.flowable.atlas.hub.AtlasHubPanel
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * A generated explorer is stale when a model in scope is newer than it — after a Design pull, and just
 * as much after a git pull or a hand edit; the Hub row and the editor banner share one predicate.
 */
class PostPullHintTest : BasePlatformTestCase() {

    fun testStaleOnlyWhenArtifactOlderThanTheChange() {
        assertTrue("newest artifact older than the change ⇒ stale", AtlasHubPanel.isExplorerStale(listOf(100L, 200L), 300L))
        assertFalse("artifact newer than the change ⇒ fresh", AtlasHubPanel.isExplorerStale(listOf(400L), 300L))
        assertFalse("no artifacts ⇒ not stale", AtlasHubPanel.isExplorerStale(emptyList(), 300L))
        assertFalse("nothing known to have changed ⇒ not stale", AtlasHubPanel.isExplorerStale(listOf(100L), null))
    }

    fun testAModelNewerThanTheArtifactIsStaleWithoutAnyPull() {
        assertTrue(AtlasExplorerStaleness.isStale(listOf(100L), 200L))
    }

    fun testTheChangeTimeIsTheNewerOfPullAndModelMtime() {
        val model = myFixture.addFileToProject("models/P.bpmn", """<definitions><process id="P" name="P"/></definitions>""")
        val index = project.service<FlowableModelIndexService>().refresh()
        assertEquals("the index carries the newest model mtime", model.virtualFile.timeStamp, index.newestModelMtime)
        // no pull recorded in this fixture → the model mtime alone decides
        assertEquals(index.newestModelMtime, AtlasExplorerStaleness.latestModelChange(project))
        assertTrue(AtlasExplorerStaleness.isStale(listOf(index.newestModelMtime - 1), AtlasExplorerStaleness.latestModelChange(project)))
        assertFalse(AtlasExplorerStaleness.isStale(listOf(index.newestModelMtime + 1), AtlasExplorerStaleness.latestModelChange(project)))
    }
}
