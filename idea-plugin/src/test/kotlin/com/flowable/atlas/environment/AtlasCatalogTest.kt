package com.flowable.atlas.environment

import com.flowable.atlas.environment.auth.AuthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The merge rule between a developer's own environments and the ones the repository ships. */
class AtlasCatalogTest {

    private fun connection(environment: String, kind: ConnectionKind, url: String, shared: Boolean = false) =
        AtlasConnection(
            id = if (shared) SharedEnvironments.connectionIdOf(environment, kind) else "${environment.lowercase()}-${kind.name.lowercase()}",
            kind = kind,
            baseUrl = url,
            username = "",
            authMode = AuthMode.BASIC,
            environmentId = environment.lowercase(),
            environmentName = environment,
            requiresConfirmation = false,
            shared = shared,
        )

    @Test
    fun `both lists are offered, the developer's first`() {
        val merged = AtlasCatalog.merge(
            listOf(connection("Local", ConnectionKind.WORK, "http://localhost:8080")),
            listOf(connection("QA", ConnectionKind.WORK, "https://work-qa.example.com", shared = true)),
        )
        assertEquals(listOf("Local", "QA"), merged.map { it.environmentName })
        assertFalse(merged[0].shared)
        assertTrue(merged[1].shared)
    }

    @Test
    fun `a local environment of the same name shadows the shared one entirely`() {
        // Two entries called QA would make every status line in the plugin ambiguous, and the local one
        // is the later, deliberate answer — most often "QA, but my own instance".
        val merged = AtlasCatalog.merge(
            listOf(connection("QA", ConnectionKind.WORK, "http://localhost:9000")),
            listOf(
                connection("QA", ConnectionKind.WORK, "https://work-qa.example.com", shared = true),
                connection("QA", ConnectionKind.DESIGN, "https://design-qa.example.com", shared = true),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals("http://localhost:9000", merged.single().baseUrl)
    }

    @Test
    fun `shadowing reads names the way people say them`() {
        val merged = AtlasCatalog.merge(
            listOf(connection(" qa ", ConnectionKind.WORK, "http://localhost:9000")),
            listOf(connection("QA", ConnectionKind.WORK, "https://work-qa.example.com", shared = true)),
        )
        assertEquals(1, merged.size)
    }

    @Test
    fun `an environment the project alone defines survives, kinds and all`() {
        val merged = AtlasCatalog.merge(
            emptyList(),
            listOf(
                connection("QA", ConnectionKind.DESIGN, "https://design-qa.example.com", shared = true),
                connection("QA", ConnectionKind.WORK, "https://work-qa.example.com", shared = true),
            ),
        )
        assertEquals(2, merged.size)
        assertTrue(merged.all { it.shared })
    }

    @Test
    fun `environments merge by the same rule as connections`() {
        val merged = AtlasCatalog.mergeEnvironments(
            listOf(AtlasEnvironmentSnapshot("qa", "QA", false)),
            listOf(
                AtlasEnvironmentSnapshot(SharedEnvironments.idOf("QA"), "QA", true),
                AtlasEnvironmentSnapshot(SharedEnvironments.idOf("PROD"), "PROD", true),
            ),
        )
        assertEquals(listOf("QA", "PROD"), merged.map { it.name })
        // The local QA's own protection setting is what applies — the shared twin is not consulted.
        assertFalse(merged.first { it.name == "QA" }.requireConfirmation)
    }

    @Test
    fun `a shared id can never collide with one a developer could produce`() {
        // Ids are slugs of names over [a-z0-9-]; the shared prefix uses a character that alphabet
        // cannot produce, so a stored pointer always says which list it points into.
        assertTrue(SharedEnvironments.isShared(SharedEnvironments.idOf("QA")))
        assertFalse(SharedEnvironments.isShared(AtlasConnectionIds.newId("shared qa", emptySet())))
        assertEquals("shared.qa", SharedEnvironments.idOf(" QA "))
        assertEquals("shared.qa-design", SharedEnvironments.connectionIdOf("QA", ConnectionKind.DESIGN))
    }
}
