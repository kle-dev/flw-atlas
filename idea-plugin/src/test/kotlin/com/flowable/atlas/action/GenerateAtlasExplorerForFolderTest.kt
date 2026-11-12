package com.flowable.atlas.action

import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.parsing.ProjectDetection.SubProject
import com.flowable.atlas.project.AtlasProjectRootService
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * *Generate Atlas Explorer…* from the Project view is about the folder that was right-clicked: on another
 * sub-project's folder it names that one and generates it, instead of the active one the click ignored.
 */
class GenerateAtlasExplorerForFolderTest : BasePlatformTestCase() {

    private val created = ArrayList<Path>()

    override fun tearDown() {
        try {
            val roots = AtlasProjectRootService.getInstance(project)
            roots.setActiveSubProject("")
            roots.setDetectedForTest(null)
            created.forEach { it.toFile().deleteRecursively() }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun dir(rel: String): VirtualFile {
        val base = Path.of(project.basePath!!)
        base.resolve(rel.substringBefore('/')).let { if (it !in created) created.add(it) }
        val path = Files.createDirectories(base.resolve(rel))
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
    }

    fun testTheDeepestSubProjectHoldingTheSelectionOwnsIt() {
        val base = Path.of("/repo")
        val detected = listOf(SubProject("apps", 1, 1), SubProject("apps/billing", 1, 1), SubProject("DEMO-orders", 1, 1))
        fun owner(rel: String) = AtlasProjectRootService.owningSubProject(base, detected, base.resolve(rel))
        assertEquals("apps/billing", owner("apps/billing/models"))
        assertEquals("the sub-project's own folder", "DEMO-orders", owner("DEMO-orders"))
        assertNull("outside every sub-project", owner("docs"))
        assertNull("a shared name prefix is no containment", AtlasProjectRootService.owningSubProject(base, listOf(SubProject("apps/bill", 1, 1)), base.resolve("apps/billing")))
    }

    fun testAFolderOfAnotherSubProjectNamesAndTargetsIt() {
        val orders = dir("DEMO-orders/models")
        val billing = dir("DEMO-billing/models")
        val roots = AtlasProjectRootService.getInstance(project)
        roots.setActiveSubProject("DEMO-orders")
        // after the folders exist: creating them drops the detection cache
        roots.setDetectedForTest(listOf(SubProject("DEMO-billing", 1, 1), SubProject("DEMO-orders", 1, 1)))
        val action = GenerateAtlasExplorerAction()

        assertEquals("DEMO-billing", action.targetSubProject(project, ActionPlaces.PROJECT_VIEW_POPUP, billing))
        assertEquals("Generate Atlas Explorer for DEMO-billing…", message("explorer.generateFor", "DEMO-billing"))
        assertNull("the active one's own folder changes nothing", action.targetSubProject(project, ActionPlaces.PROJECT_VIEW_POPUP, orders))
        assertNull("the Tools menu has no folder under a cursor", action.targetSubProject(project, ActionPlaces.MAIN_MENU, billing))
    }

    fun testNothingChangesBeforeDetectionOrInASingleProject() {
        val billing = dir("DEMO-billing/models")
        val roots = AtlasProjectRootService.getInstance(project)
        roots.setDetectedForTest(null)
        assertNull(GenerateAtlasExplorerAction().targetSubProject(project, ActionPlaces.PROJECT_VIEW_POPUP, billing))
        roots.setDetectedForTest(listOf(SubProject("DEMO-billing", 1, 1)))
        assertNull("one project: there is nothing to switch between", GenerateAtlasExplorerAction().targetSubProject(project, ActionPlaces.PROJECT_VIEW_POPUP, billing))
    }
}
