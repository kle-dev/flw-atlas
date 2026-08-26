package com.flowable.atlas.environment

import com.flowable.atlas.design.DesignAuthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentLinksTest {

    private fun env(id: String, name: String, protected: Boolean = false) =
        AtlasEnvironmentSnapshot(id, name, protected)

    private fun conn(id: String, environmentId: String, kind: ConnectionKind, url: String) =
        AtlasConnection(
            id = id,
            kind = kind,
            baseUrl = url,
            username = "",
            authMode = DesignAuthMode.BASIC,
            environmentId = environmentId,
            environmentName = environmentId.uppercase(),
            requiresConfirmation = false,
        )

    @Test
    fun `groups follow the environment order, kinds their declared order`() {
        // Catalog order is the user's DEV → QA pipeline; connection order is whatever was added when,
        // which is exactly what must not decide how the chooser reads.
        val groups = EnvironmentLinks.grouped(
            listOf(env("dev", "DEV"), env("qa", "QA")),
            listOf(
                conn("qa-work", "qa", ConnectionKind.WORK, "https://work-qa.example.com"),
                conn("dev-control", "dev", ConnectionKind.CONTROL, "http://localhost:8081/flowable-control"),
                conn("dev-design", "dev", ConnectionKind.DESIGN, "http://design-dev.example.com"),
            ),
        )
        assertEquals(listOf("DEV", "QA"), groups.map { it.environment.name })
        assertEquals(
            listOf(ConnectionKind.DESIGN, ConnectionKind.CONTROL),
            groups.first().links.map { it.kind },
        )
        assertEquals(listOf(ConnectionKind.WORK), groups.last().links.map { it.kind })
    }

    @Test
    fun `an environment with nothing to open is not a group`() {
        val groups = EnvironmentLinks.grouped(
            listOf(env("dev", "DEV"), env("empty", "EMPTY")),
            listOf(conn("dev-hub", "dev", ConnectionKind.HUB, "https://hub.example.com")),
        )
        assertEquals(listOf("DEV"), groups.map { it.environment.name })
    }

    @Test
    fun `a connection with no url yet is dropped rather than offered`() {
        // The settings page lets a connection exist before its URL is typed; a menu entry that opens
        // nothing is worse than one that is not there.
        val groups = EnvironmentLinks.grouped(
            listOf(env("dev", "DEV")),
            listOf(
                conn("dev-hub", "dev", ConnectionKind.HUB, "  "),
                conn("dev-work", "dev", ConnectionKind.WORK, "http://localhost:8080"),
            ),
        )
        assertEquals(listOf(ConnectionKind.WORK), groups.single().links.map { it.kind })
    }

    @Test
    fun `emptiness is what a control's enabled state asks about`() {
        assertTrue(EnvironmentLinks.isEmpty(emptyList(), emptyList()))
        assertTrue(
            "an environment with no URLs is nothing to open",
            EnvironmentLinks.isEmpty(listOf(env("dev", "DEV")), listOf(conn("c", "dev", ConnectionKind.HUB, ""))),
        )
        assertFalse(
            EnvironmentLinks.isEmpty(
                listOf(env("dev", "DEV")),
                listOf(conn("c", "dev", ConnectionKind.HUB, "https://hub.example.com")),
            ),
        )
    }
}
