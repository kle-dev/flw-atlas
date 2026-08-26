package com.flowable.atlas.environment

import com.flowable.atlas.environment.auth.AuthMode
import com.flowable.atlas.environment.AtlasConnectionSelection.Resolution
import org.junit.Assert.assertEquals
import org.junit.Test

/** The three resolution rules — the part of the selection that must never guess wrong. */
class AtlasConnectionSelectionTest {

    private fun connection(id: String, env: String, url: String) = AtlasConnection(
        id = id,
        kind = ConnectionKind.DESIGN,
        baseUrl = url,
        username = "demo",
        authMode = AuthMode.BASIC,
        environmentId = env,
        environmentName = env.uppercase(),
        requiresConfirmation = false,
    )

    private val dev1 = connection("dev1-design", "dev1", "http://design-dev1.example.com")
    private val prod = connection("prod-design", "prod", "http://design.example.com")

    private fun resolve(pointer: String?, candidates: List<AtlasConnection> = listOf(dev1, prod)) =
        AtlasConnectionSelection.resolve(pointer, candidates)

    @Test
    fun `an explicit pointer wins`() {
        assertEquals(Resolution.Selected(prod, explicit = true), resolve("prod-design"))
    }

    @Test
    fun `a pointer to a deleted connection resolves to dangling, never to another connection`() {
        // The nightmare this rule prevents: the user deletes DEV, and the next pull silently runs
        // against PROD because "there is only one left".
        assertEquals(Resolution.Dangling("gone"), resolve("gone", candidates = listOf(prod)))
    }

    @Test
    fun `with no pointer a single connection is used`() {
        // This is what makes the single-environment user notice nothing at all: no configuration, no
        // dropdown to operate, and a Design pull that keeps working whatever the Work selection is.
        assertEquals(Resolution.Selected(prod, explicit = false), resolve(null, candidates = listOf(prod)))
    }

    @Test
    fun `with two connections and no pointer nothing is guessed`() {
        assertEquals(Resolution.NotSet, resolve(null))
    }

    @Test
    fun `an empty catalog resolves to nothing`() {
        assertEquals(Resolution.NotSet, resolve(null, candidates = emptyList()))
        assertEquals(Resolution.Dangling("dev1-design"), resolve("dev1-design", candidates = emptyList()))
    }

    @Test
    fun `a blank pointer is treated as never chosen`() {
        assertEquals(Resolution.Selected(prod, explicit = false), resolve("", candidates = listOf(prod)))
    }

    @Test
    fun `not set, once chosen, is never talked out of by the single-connection rule`() {
        // The bug this is here for: with one environment defined, picking "not set" unset the pointer,
        // the rule above answered with that same environment, and the Atlas Hub kept showing its
        // workspace and apps under a picker that read "not set".
        assertEquals(Resolution.NotSet, resolve(AtlasConnectionSelection.NONE, candidates = listOf(prod)))
        assertEquals(Resolution.NotSet, resolve(AtlasConnectionSelection.NONE))
        // Nor is it a pointer at something deleted: nothing was deleted, the user said no.
        assertEquals(Resolution.NotSet, resolve(AtlasConnectionSelection.NONE, candidates = emptyList()))
    }

    @Test
    fun `no environment can be named into a collision with the not-set marker`() {
        // Ids are `[a-z0-9-]+`, so an environment called "@none" — or anything else — slugs to
        // something that cannot be it.
        assertEquals("none", AtlasConnectionIds.slug("@none"))
        assertEquals(Resolution.Dangling("none"), resolve("none", candidates = listOf(prod)))
    }
}
