package com.flowable.atlas.action

import com.flowable.atlas.project.AtlasProjectRootService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files

/**
 * What the generated page is called. The name has to describe what was analysed — which is the *active
 * Flowable project*, not the folder the IDE happens to be opened on.
 */
class GenerateAtlasExplorerNamingTest : BasePlatformTestCase() {

    private val action = GenerateAtlasExplorerAction()

    fun testWithoutASubProjectTheNameIsTheProjectItself() {
        assertEquals(sanitized(project.name), action.safeName(project))
    }

    fun testTheNameFollowsTheActiveFlowableProject() {
        // The case this exists for: the IDE is opened on a folder that *contains* the Flowable project.
        // The page used to arrive called after that parent, which says nothing about what is in it,
        // while the Atlas Hub had been analysing the sub-project all along.
        val rootService = AtlasProjectRootService.getInstance(project)
        val base = java.nio.file.Path.of(project.basePath!!)
        val sub = base.resolve("orders-app")
        Files.createDirectories(sub)
        try {
            rootService.setActiveSubProject("orders-app")
            assertEquals("orders-app", action.safeName(project))
        } finally {
            rootService.setActiveSubProject("")
            Files.deleteIfExists(sub)
        }
    }

    fun testAStaleSubProjectFallsBackRatherThanNamingAFolderThatIsGone() {
        // activeProjectDir() widens to the project base when the stored sub-project no longer resolves,
        // and the name has to follow it — naming a deleted folder would be a page nobody can place.
        val rootService = AtlasProjectRootService.getInstance(project)
        try {
            rootService.setActiveSubProject("was-renamed-away")
            assertEquals(sanitized(project.name), action.safeName(project))
        } finally {
            rootService.setActiveSubProject("")
        }
    }

    private fun sanitized(raw: String) = raw.replace(Regex("""[^A-Za-z0-9._-]"""), "-")
}
