package com.flowable.atlas.hub

import com.flowable.atlas.design.DesignPullSelection
import com.flowable.atlas.environment.AtlasConnectionSelection.Resolution
import com.flowable.atlas.environment.ConnectionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The header says one thing, in the order of what goes wrong first. */
class HubAttentionTest {

    private fun snapshot(
        design: Resolution = Resolution.NotSet,
        work: Resolution = Resolution.NotSet,
        awaiting: Int = 0,
        skipped: List<String> = emptyList(),
        stale: Boolean = false,
        failed: String? = null,
    ) = HubSnapshot(
        subProjects = emptyList(), activeSubProject = "", projectsAwaitingChoice = awaiting,
        modelCount = if (failed == null) 3 else null, indexFailure = failed, typeCounts = emptyList(), scopeLabel = null, builtAtMillis = 1L,
        skippedArchives = skipped, artifacts = emptyList(), recentModels = emptyList(), explorerStale = stale, changedModels = if (stale) listOf("DEMO-P001") else emptyList(), browserAvailable = false,
        designResolution = design, workResolution = work, hasAnyEnvironment = false,
        pullSelection = DesignPullSelection.EMPTY, lastPullMillis = null, searchedIn = "atlas-output/",
    )

    @Test
    fun aCleanPanelHasNothingToSay() {
        assertNull(HubAttention.of(snapshot()))
    }

    @Test
    fun aFailedIndexIsSaidBeforeWhatTheIndexWouldHaveShown() {
        // nothing below the index can be judged without it — but a wrong server and an unchosen project still come first
        assertEquals(HubAttention.IndexFailed("boom"), HubAttention.of(snapshot(failed = "boom", skipped = listOf("a.bar"), stale = true)))
        assertEquals(HubAttention.ChooseProject(2), HubAttention.of(snapshot(failed = "boom", awaiting = 2)))
    }

    @Test
    fun aRemovedEnvironmentBeatsEverythingElse() {
        // The next click would hit the wrong server — nothing else on the panel matters until that is fixed.
        val all = snapshot(design = Resolution.Dangling("gone"), awaiting = 3, skipped = listOf("a.bar"), stale = true)
        assertEquals(HubAttention.RemovedEnvironment(ConnectionKind.DESIGN), HubAttention.of(all))
        assertEquals(
            HubAttention.RemovedEnvironment(ConnectionKind.WORK),
            HubAttention.of(snapshot(work = Resolution.Dangling("gone"), awaiting = 3, stale = true)),
        )
    }

    @Test
    fun anUnchosenProjectBeatsArchivesAndStaleness() {
        assertEquals(HubAttention.ChooseProject(3), HubAttention.of(snapshot(awaiting = 3, skipped = listOf("a.bar"), stale = true)))
    }

    @Test
    fun unreadableArchivesBeatAStaleExplorer() {
        // Data Atlas could not see comes before artifact drift: the explorer is stale *because* of what it saw.
        assertEquals(HubAttention.UnreadableArchives(listOf("a.bar")), HubAttention.of(snapshot(skipped = listOf("a.bar"), stale = true)))
        assertEquals(HubAttention.StaleExplorer(listOf("DEMO-P001")), HubAttention.of(snapshot(stale = true)))
    }
}
