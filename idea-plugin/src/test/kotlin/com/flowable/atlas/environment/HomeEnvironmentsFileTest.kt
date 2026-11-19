package com.flowable.atlas.environment

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * The home-directory copy of the environment list — what keeps it under Remote Development, where each
 * backend has a configuration of its own. Every catalog here is a fresh instance pointed at a temp file,
 * standing in for one IDE process; the application's own catalog, and the developer's real home, are
 * never touched. A platform test only because a mutation publishes on the message bus.
 */
class HomeEnvironmentsFileTest : BasePlatformTestCase() {

    private lateinit var file: Path

    override fun setUp() {
        super.setUp()
        file = Files.createTempDirectory("atlas-home").resolve("nested").resolve("environments.xml")
    }

    private fun process(): AtlasEnvironments = AtlasEnvironments().also { it.homeFile = file }

    fun testTheApplicationCatalogHasNoHomeFileInTests() {
        assertNull("a test must never reach the developer's home", AtlasEnvironments.getInstance().homeFile)
    }

    fun testABackendThatNeverSawTheListReadsItFromHome() {
        val first = process()
        val dev = first.addEnvironment("DEV")
        first.addConnection(dev, ConnectionKind.DESIGN, "http://localhost:10014", "admin")
        val qa = first.addEnvironment("QA", requireConfirmation = true)
        first.addConnection(qa, ConnectionKind.WORK, "https://qa.example.com/work")

        val second = process().apply { noStateLoaded() }

        assertEquals(listOf("DEV", "QA"), second.environments().map { it.name })
        assertEquals(listOf("dev-design", "qa-work"), second.connections().map { it.id })
        assertTrue(second.environment(qa)!!.requireConfirmation)
        assertEquals("admin", second.connection("dev-design")!!.username)
    }

    fun testTheHomeFileWinsOverTheListInTheIdeConfiguration() {
        process().addEnvironment("QA")

        val stale = process().apply {
            loadState(AtlasEnvironments.State().also { it.environments = mutableListOf(AtlasEnvironments.EnvironmentState("dev", "DEV")) })
        }

        assertEquals(listOf("QA"), stale.environments().map { it.name })
    }

    fun testAnExistingInstallationSeedsTheHomeFile() {
        process().loadState(
            AtlasEnvironments.State().also { it.environments = mutableListOf(AtlasEnvironments.EnvironmentState("dev", "DEV")) },
        )

        assertTrue(Files.isRegularFile(file))
        assertEquals(listOf("DEV"), process().apply { noStateLoaded() }.environments().map { it.name })
    }

    fun testAMutationStartsFromWhatAnotherProcessWrote() {
        val first = process()
        first.addEnvironment("DEV")
        val second = process().apply { noStateLoaded() }
        second.addEnvironment("QA")
        // A coarse filesystem clock must not make the two writes look like one.
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000))

        first.addEnvironment("UAT")

        assertEquals(listOf("DEV", "QA", "UAT"), first.environments().map { it.name })
        assertEquals(listOf("DEV", "QA", "UAT"), process().apply { noStateLoaded() }.environments().map { it.name })
    }

    fun testRemovingEverythingIsRememberedAsAnEmptyList() {
        val first = process()
        val dev = first.addEnvironment("DEV")
        first.removeEnvironment(dev)

        val second = process().apply {
            loadState(AtlasEnvironments.State().also { it.environments = mutableListOf(AtlasEnvironments.EnvironmentState("old", "OLD")) })
        }

        assertTrue(second.environments().isEmpty())
    }

    fun testAnUnreadableHomeFileLeavesTheIdeListAlone() {
        Files.createDirectories(file.parent)
        Files.writeString(file, "<not xml")

        val catalog = process().apply {
            loadState(AtlasEnvironments.State().also { it.environments = mutableListOf(AtlasEnvironments.EnvironmentState("dev", "DEV")) })
        }

        assertEquals(listOf("DEV"), catalog.environments().map { it.name })
    }
}
