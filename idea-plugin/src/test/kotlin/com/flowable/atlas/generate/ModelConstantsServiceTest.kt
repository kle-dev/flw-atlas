package com.flowable.atlas.generate

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.settings.GenerationConstantsConfigurable
import com.intellij.openapi.components.service
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * When the generated model-constants class is rewritten behind the user's back, and when it is not: the
 * class belongs to the Flowable project it was generated from, and *Keep the generated class in sync* is
 * the user's switch, not something a manual Generate turns back on.
 */
class ModelConstantsServiceTest : BasePlatformTestCase() {

    private val state get() = ModelConstantsSettings.getInstance(project).state

    override fun tearDown() {
        try {
            // Project-level state outlives a light test; a class left configured here would be "kept in
            // sync" by the next test's index updates.
            state.fqcn = ""
            state.sourceRootUrl = ""
            state.autoRefresh = true
            state.subProject = ""
            AtlasProjectRootService.getInstance(project).setActiveSubProject("")
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun sourceRoot(): VirtualFile = ProjectRootManager.getInstance(project).contentSourceRoots.first()

    private fun generate(): VirtualFile {
        val result = ModelConstantsService.getInstance(project).generateAndWrite("demo.DemoKeys", sourceRoot())
        return (result as ModelConstantsService.Result.Written).file
    }

    /** Rebuild the index, run the auto-refresh, and let its EDT write land. */
    private fun syncAfterModelChange() {
        project.service<FlowableModelIndexService>().refresh()
        ModelConstantsService.getInstance(project).refresh()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun text(file: VirtualFile) = VfsUtilCore.loadText(file)

    fun testTheClassIsLeftAloneWhileAnotherFlowableProjectIsActive() {
        myFixture.addFileToProject("models/DEMO-P001.bpmn", """<definitions><process id="DEMO-P001" name="Order"/></definitions>""")
        project.service<FlowableModelIndexService>().refresh()
        val file = generate()
        val generated = text(file)
        assertTrue(generated, generated.contains("DEMO-P001"))
        assertEquals("the class remembers the project it was generated from", "", state.subProject)

        val roots = AtlasProjectRootService.getInstance(project)
        // In a light test the key resolves to no folder, and that is the point: the guard compares the
        // stored key, so even a scope that widens back to the repository is not the class's scope.
        roots.setActiveSubProject("DEMO-other-app")
        myFixture.addFileToProject("models/DEMO-P002.bpmn", """<definitions><process id="DEMO-P002" name="Billing"/></definitions>""")
        syncAfterModelChange()
        assertEquals("switching the Hub's project must not rewrite the class", generated, text(file))

        roots.setActiveSubProject("")
        syncAfterModelChange()
        assertTrue("back on its own project the class is synced again", text(file).contains("DEMO-P002"))
    }

    fun testGeneratingByHandDoesNotSwitchSyncBackOn() {
        myFixture.addFileToProject("models/DEMO-P001.bpmn", """<definitions><process id="DEMO-P001" name="Order"/></definitions>""")
        project.service<FlowableModelIndexService>().refresh()
        state.autoRefresh = false
        val file = generate()
        assertFalse("a manual Generate must leave the user's choice alone", state.autoRefresh)

        val generated = text(file)
        myFixture.addFileToProject("models/DEMO-P002.bpmn", """<definitions><process id="DEMO-P002" name="Billing"/></definitions>""")
        syncAfterModelChange()
        assertEquals("with sync off, a model change leaves the class as it was", generated, text(file))
    }

    fun testTheSettingsPageNamesTheProjectTheClassFollows() {
        val page = GenerationConstantsConfigurable(project)
        state.fqcn = ""
        assertFalse(page.syncComment(state).contains("Kept in sync while"))
        state.fqcn = "demo.DemoKeys"
        state.subProject = "apps/DEMO-orders"
        assertTrue(page.syncComment(state), page.syncComment(state).contains("Kept in sync while apps/DEMO-orders is the active Flowable project."))
        state.subProject = ""
        assertFalse("one Flowable project: there is no other to follow", page.syncComment(state, projects = 1).contains("Kept in sync while"))
        assertTrue(page.syncComment(state, projects = 2), page.syncComment(state, projects = 2).contains("the whole repository"))
    }
}
