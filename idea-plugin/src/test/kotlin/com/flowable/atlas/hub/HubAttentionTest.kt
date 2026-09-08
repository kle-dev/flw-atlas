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
    ) = HubSnapshot(
        subProjects = emptyList(), activeSubProject = "", projectsAwaitingChoice = awaiting,
        modelCount = 3, typeCounts = emptyList(), scopeLabel = null, builtAtMillis = 1L,
        skippedArchives = skipped, artifacts = emptyList(), explorerStale = stale, browserAvailable = false,
        designResolution = design, workResolution = work, hasAnyEnvironment = false,
        pullSelection = DesignPullSelection.EMPTY, lastPullMillis = null, searchedIn = "atlas-output/",
    )

    @Test
    fun aCleanPanelHasNothingToSay() {
        assertNull(HubAttention.of(snapshot()))
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
        assertEquals(HubAttention.StaleExplorer, HubAttention.of(snapshot(stale = true)))
    }
}
