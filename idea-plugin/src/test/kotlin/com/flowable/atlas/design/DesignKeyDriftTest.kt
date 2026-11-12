package com.flowable.atlas.design

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The post-pull key-drift computation: which model keys the project had before a pull are gone after it,
 * and what the balloon then offers. DEMO-* placeholder keys — this repo is public.
 */
class DesignKeyDriftTest {

    @Test fun noBaselineYieldsNothing() {
        // First pull of a session: no cached index → nothing to compare against, so never a false alarm.
        assertEquals(emptyList<String>(), removedModelKeys(null, setOf("DEMO-P1", "DEMO-P2")))
    }

    @Test fun removedKeysAreReturnedSorted() {
        assertEquals(
            listOf("DEMO-A", "DEMO-C"),
            removedModelKeys(setOf("DEMO-C", "DEMO-A", "DEMO-B"), setOf("DEMO-B")),
        )
    }

    @Test fun nothingRemovedYieldsEmpty() {
        // Added keys don't count as drift; only disappearances do.
        assertEquals(emptyList<String>(), removedModelKeys(setOf("DEMO-A", "DEMO-B"), setOf("DEMO-A", "DEMO-B", "DEMO-C")))
    }

    @Test fun removedKeysOfferToFindWhatTheyBrokeFirst() {
        // The warning is about the removed keys, so the way to what they broke leads the balloon.
        assertEquals(
            listOf(PullAction.FIND_BROKEN, PullAction.REGENERATE, PullAction.SIGN_OUT_RETRY),
            pullBalloonActions(listOf("DEMO-A"), hasExplorer = true, unauthorized = true),
        )
        assertEquals(listOf(PullAction.FIND_BROKEN), pullBalloonActions(listOf("DEMO-A"), hasExplorer = false, unauthorized = false))
    }

    @Test fun nothingRemovedOffersNothingToFind() {
        assertEquals(listOf(PullAction.REGENERATE), pullBalloonActions(emptyList(), hasExplorer = true, unauthorized = false))
        assertEquals(emptyList<PullAction>(), pullBalloonActions(emptyList(), hasExplorer = false, unauthorized = false))
    }
}
