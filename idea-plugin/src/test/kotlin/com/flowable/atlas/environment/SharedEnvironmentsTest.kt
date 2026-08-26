package com.flowable.atlas.environment

import com.flowable.atlas.design.DesignAuthMode
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer

/**
 * The committed environment file: what it holds, what it can never hold, and what a hand-edited or
 * badly merged one does to the plugin.
 */
class SharedEnvironmentsTest : BasePlatformTestCase() {

    private fun store() = SharedEnvironments.getInstance(project)

    override fun tearDown() {
        try {
            store().environments().forEach { store().unshare(it.name) }
        } finally {
            super.tearDown()
        }
    }

    fun testSharingWritesTheUrlsAndNothingElse() {
        store().share(
            "QA", requireConfirmation = true,
            listOf(
                connection(ConnectionKind.DESIGN, "https://design-qa.example.com", "kevin", DesignAuthMode.ACCESS_TOKEN),
                connection(ConnectionKind.WORK, "https://work-qa.example.com", "kevin"),
            ),
        )
        val xml = com.intellij.openapi.util.JDOMUtil.write(XmlSerializer.serialize(store().state))
        // The point of the whole feature: the file is safe to commit. The username was passed in and is
        // simply not part of the schema, so no future caller can put one there either.
        assertFalse("no username reaches the file", xml.contains("kevin"))
        assertFalse("no credential field exists at all", xml.contains("username", ignoreCase = true))
        assertTrue(xml.contains("https://design-qa.example.com"))
        assertTrue(xml.contains("https://work-qa.example.com"))
        // The auth *mode* is a fact about the server, not a secret, and saves the next person a guess.
        assertTrue(xml.contains("ACCESS_TOKEN"))
    }

    fun testSharedConnectionsCarryNoUsernameAndSayWhereTheyCameFrom() {
        store().share("QA", false, listOf(connection(ConnectionKind.WORK, "https://work-qa.example.com", "kevin")))
        val connection = store().connections(ConnectionKind.WORK).single()
        assertEquals("QA", connection.environmentName)
        assertEquals("https://work-qa.example.com", connection.baseUrl)
        assertEquals("", connection.username)
        assertTrue(connection.shared)
        assertEquals(SharedEnvironments.connectionIdOf("QA", ConnectionKind.WORK), connection.id)
    }

    fun testSharingTheSameNameTwiceReplacesItRatherThanDuplicating() {
        store().share("QA", false, listOf(connection(ConnectionKind.WORK, "https://old.example.com")))
        store().share("qa", true, listOf(connection(ConnectionKind.WORK, "https://new.example.com")))
        val environment = store().environments().single()
        assertEquals("qa", environment.name)
        assertTrue("the second share's protection wins", environment.requireConfirmation)
        assertEquals("https://new.example.com", store().connections().single().baseUrl)
    }

    fun testUnshareTakesItOutOfTheFile() {
        store().share("QA", false, listOf(connection(ConnectionKind.WORK, "https://work-qa.example.com")))
        store().unshare(" qa ")
        assertTrue(store().isEmpty())
    }

    fun testAConnectionWithoutAUrlIsNotShared() {
        store().share(
            "QA", false,
            listOf(connection(ConnectionKind.WORK, ""), connection(ConnectionKind.DESIGN, "https://design.example.com")),
        )
        assertEquals(listOf(ConnectionKind.DESIGN), store().connections().map { it.kind })
    }

    fun testABadlyMergedFileIsRepairedRatherThanTrusted() {
        val state = SharedEnvironments.State().apply {
            environments = mutableListOf(
                SharedEnvironments.EnvironmentState("", false, emptyList()),
                SharedEnvironments.EnvironmentState(
                    " QA ", false,
                    listOf(
                        SharedEnvironments.ConnectionState(ConnectionKind.WORK, "https://first.example.com"),
                        SharedEnvironments.ConnectionState(ConnectionKind.WORK, "https://second.example.com"),
                        SharedEnvironments.ConnectionState().apply { kind = "NONSENSE"; baseUrl = "https://x" },
                    ),
                ),
                SharedEnvironments.EnvironmentState("qa", false, listOf()),
            )
        }
        val repaired = SharedEnvironments.sanitize(state)
        val environment = repaired.environments.single()
        assertEquals("a nameless environment means nothing and is dropped", "qa", environment.name)
        assertTrue("the later side of a bad merge wins, as everywhere else", environment.connections.isEmpty())
    }

    private fun connection(
        kind: ConnectionKind,
        url: String,
        username: String = "",
        authMode: DesignAuthMode = DesignAuthMode.BASIC,
    ) = AtlasConnection(
        id = "local-${kind.name.lowercase()}",
        kind = kind,
        baseUrl = url,
        username = username,
        authMode = authMode,
        environmentId = "local",
        environmentName = "QA",
        requiresConfirmation = false,
    )
}
