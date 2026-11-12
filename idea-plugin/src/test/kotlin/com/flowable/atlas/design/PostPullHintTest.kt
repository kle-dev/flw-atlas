package com.flowable.atlas.design

import com.flowable.atlas.explorer.AtlasExplorerStaleness
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * A generated explorer is stale when a model in scope is newer than it — after a Design pull, and just
 * as much after a git pull or a hand edit; the Hub row and the editor banner share one predicate.
 */
class PostPullHintTest : BasePlatformTestCase() {

    fun testStaleOnlyWhenArtifactOlderThanTheChange() {
        assertTrue("newest artifact older than the change ⇒ stale", AtlasExplorerStaleness.isStale(listOf(100L, 200L), 300L))
        assertFalse("artifact newer than the change ⇒ fresh", AtlasExplorerStaleness.isStale(listOf(400L), 300L))
        assertFalse("no artifacts ⇒ not stale", AtlasExplorerStaleness.isStale(emptyList(), 300L))
        assertFalse("nothing known to have changed ⇒ not stale", AtlasExplorerStaleness.isStale(listOf(100L), null))
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

    fun testTheChangedModelsAreNamedByKey() {
        myFixture.addFileToProject(
            "models/DEMO-P007.bpmn20.xml",
            """<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"><process id="DEMO-P007" name="Seven"/></definitions>""",
        )
        myFixture.addFileToProject("models/DEMO-F002.form", """{"key":"DEMO-F002","name":"Two"}""")
        val index = project.service<FlowableModelIndexService>().index()
        assertEquals("every scanned file has its time", 2, index.fileMtimes.size)
        val oldest = index.fileMtimes.values.min()
        assertEquals(listOf("DEMO-F002", "DEMO-P007"), AtlasExplorerStaleness.changedSince(index, oldest - 1))
        assertTrue("a page newer than every model has nothing to name", AtlasExplorerStaleness.changedSince(index, index.newestModelMtime + 1).isEmpty())
        assertEquals(
            "2 models changed since this page was generated: DEMO-F002, DEMO-P007",
            AtlasExplorerStaleness.changedSummary(listOf("DEMO-F002", "DEMO-P007")),
        )
        assertEquals(
            "7 models changed since this page was generated: A, B, C, D, E +2 more",
            AtlasExplorerStaleness.changedSummary(listOf("A", "B", "C", "D", "E", "F", "G")),
        )
        assertEquals("Models changed since this page was generated.", AtlasExplorerStaleness.changedSummary(emptyList()))
    }
}
