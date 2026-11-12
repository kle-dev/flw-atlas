package com.flowable.atlas.environment

import com.flowable.atlas.environment.auth.AuthMode
import com.flowable.atlas.environment.AtlasEnvironments.ConnectionState
import com.flowable.atlas.environment.AtlasEnvironments.EnvironmentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the catalog through [AtlasEnvironments.loadState] and its read API only — the mutators
 * publish on a project message bus, which needs a running application, so they are covered by the
 * platform test instead. Everything interesting about validity lives in `sanitize`, which `loadState`
 * runs, so this is where the invariants are pinned.
 */
class AtlasEnvironmentsStateTest {

    private fun catalog(
        environments: List<EnvironmentState> = emptyList(),
        connections: List<ConnectionState> = emptyList(),
    ): AtlasEnvironments = AtlasEnvironments().apply {
        loadState(
            AtlasEnvironments.State().also {
                it.environments = environments.toMutableList()
                it.connections = connections.toMutableList()
            },
        )
    }

    private fun design(id: String, env: String, url: String, authMode: AuthMode = AuthMode.BASIC) =
        ConnectionState(id, env, ConnectionKind.DESIGN, url, "demo", authMode)

    private fun work(id: String, env: String, url: String) =
        ConnectionState(id, env, ConnectionKind.WORK, url, "demo")

    @Test
    fun `an environment with only a work connection is valid`() {
        // The case the maintainer named: QA has a running app and no Design server. Pulling models is
        // still possible, because the Design pointer is a separate choice entirely.
        val catalog = catalog(
            listOf(EnvironmentState("qa", "QA")),
            listOf(work("qa-work", "qa", "http://work-qa.example.com")),
        )
        assertEquals(1, catalog.environments().size)
        assertEquals(emptyList<AtlasConnection>(), catalog.connections(ConnectionKind.DESIGN))
        assertNotNull(catalog.connection("qa", ConnectionKind.WORK))
        assertFalse(catalog.hasBothServers("qa"))
        assertEquals(setOf(ConnectionKind.WORK), catalog.occupiedKinds("qa"))
    }

    @Test
    fun `an environment with only a design connection is valid`() {
        val catalog = catalog(
            listOf(EnvironmentState("dev1", "DEV1")),
            listOf(design("dev1-design", "dev1", "http://design-dev1.example.com")),
        )
        assertNull(catalog.connection("dev1", ConnectionKind.WORK))
        assertEquals(setOf(ConnectionKind.DESIGN), catalog.occupiedKinds("dev1"))
    }

    @Test
    fun `a second connection of the same kind in one environment is dropped`() {
        val catalog = catalog(
            listOf(EnvironmentState("qa", "QA")),
            listOf(
                work("qa-work", "qa", "http://work-qa.example.com"),
                work("qa-work-2", "qa", "http://other-qa.example.com"),
            ),
        )
        assertEquals(
            "one connection per environment and kind is what lets every picker list environment names",
            listOf("qa-work"),
            catalog.connections().map { it.id },
        )
    }

    @Test
    fun `two environments may share one server`() {
        // One Design server hosting a DEV workspace and a QA workspace is an ordinary setup. They share
        // the single saved credential that URL has, which is right: same server, same login.
        val catalog = catalog(
            listOf(EnvironmentState("dev1", "DEV1"), EnvironmentState("qa", "QA")),
            listOf(
                design("dev1-design", "dev1", "http://design.example.com/flowable-design"),
                design("qa-design", "qa", "http://design.example.com/flowable-design/"),
            ),
        )
        assertEquals(listOf("dev1-design", "qa-design"), catalog.connections().map { it.id })
        assertEquals(2, catalog.byBaseUrl(ConnectionKind.DESIGN, "http://design.example.com/flowable-design").size)
    }

    @Test
    fun `the same url under different kinds is not a duplicate`() {
        // One host serving both Design and a running app is an ordinary all-in-one dev setup, and the
        // two keychain records are already namespaced by kind.
        val catalog = catalog(
            listOf(EnvironmentState("local", "Local")),
            listOf(
                design("local-design", "local", "http://localhost:8080"),
                work("local-work", "local", "http://localhost:8080"),
            ),
        )
        assertEquals(2, catalog.connections().size)
        assertTrue(catalog.hasBothServers("local"))
    }

    @Test
    fun `a connection with no kind is dropped rather than defaulting to design`() {
        // Guards the SkipDefaultsSerializationFilter trap that made `kind` a String: a record whose
        // kind is missing must never be silently adopted as the enum's first constant.
        val kindless = ConnectionState().apply {
            id = "mystery"
            environmentId = "dev1"
            baseUrl = "http://design-dev1.example.com"
        }
        val catalog = catalog(listOf(EnvironmentState("dev1", "DEV1")), listOf(kindless))
        assertEquals(emptyList<AtlasConnection>(), catalog.connections())
    }

    @Test
    fun `a connection whose environment is gone is re-homed into a protected one, never dropped`() {
        val catalog = catalog(
            emptyList(),
            listOf(design("orphan", "deleted-env", "http://design.example.com/flowable-design")),
        )
        val recovered = catalog.connections().single()
        assertEquals("http://design.example.com/flowable-design", recovered.baseUrl)
        assertEquals("losing a connection loses a URL", "design.example.com", recovered.environmentName)
        assertTrue("an environment we know nothing about is the one worth asking about", recovered.requiresConfirmation)
    }

    @Test
    fun `two orphans on one host are re-homed into the same environment`() {
        val catalog = catalog(
            emptyList(),
            listOf(
                design("orphan-design", "gone", "http://all.example.com/flowable-design"),
                work("orphan-work", "gone", "http://all.example.com/flowable-work"),
            ),
        )
        assertEquals(1, catalog.environments().size)
        assertEquals(2, catalog.connections().size)
    }

    @Test
    fun `getState prunes invalid entries but never reorders the environment list`() {
        // DEV/QA/UAT/PROD is the order in the user's head; sorting would put PROD second.
        val catalog = catalog(
            listOf(
                EnvironmentState("dev", "DEV"),
                EnvironmentState("", "nameless id"),
                EnvironmentState("blank", ""),
                EnvironmentState("prod", "PROD"),
                EnvironmentState("qa", "QA"),
            ),
        )
        assertEquals(listOf("DEV", "PROD", "QA"), catalog.getState().environments.map { it.name })
    }

    @Test
    fun `duplicate ids collapse, last wins`() {
        val catalog = catalog(
            listOf(EnvironmentState("qa", "QA old"), EnvironmentState("qa", "QA new")),
        )
        assertEquals(listOf("QA new"), catalog.environments().map { it.name })
    }

    @Test
    fun `authMode defaults to basic and a token-mode connection keeps it`() {
        val catalog = catalog(
            listOf(EnvironmentState("dev1", "DEV1"), EnvironmentState("prod", "PROD", requireConfirmation = true)),
            listOf(
                design("dev1-design", "dev1", "http://design-dev1.example.com"),
                design("prod-design", "prod", "http://design.example.com", AuthMode.ACCESS_TOKEN),
            ),
        )
        assertEquals(AuthMode.BASIC, catalog.connection("dev1-design")?.authMode)
        assertEquals(AuthMode.ACCESS_TOKEN, catalog.connection("prod-design")?.authMode)
    }

    @Test
    fun `protection is inherited from the environment, so a later connection cannot miss it`() {
        val catalog = catalog(
            listOf(EnvironmentState("prod", "PROD", requireConfirmation = true)),
            listOf(work("prod-work", "prod", "http://work.example.com")),
        )
        val connection = catalog.connection("prod", ConnectionKind.WORK)!!
        assertTrue(connection.requiresConfirmation)
        assertEquals("PROD · Work", connection.displayName)
    }

    @Test
    fun `byBaseUrl matches across a trailing slash and stays within its kind`() {
        val catalog = catalog(
            listOf(EnvironmentState("dev1", "DEV1")),
            listOf(design("dev1-design", "dev1", "http://design.example.com/flowable-design")),
        )
        assertEquals(1, catalog.byBaseUrl(ConnectionKind.DESIGN, "http://design.example.com/flowable-design/").size)
        assertEquals(0, catalog.byBaseUrl(ConnectionKind.WORK, "http://design.example.com/flowable-design").size)
    }
}
