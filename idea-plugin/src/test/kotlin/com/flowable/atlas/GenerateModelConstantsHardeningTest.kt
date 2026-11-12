package com.flowable.atlas

import com.flowable.atlas.action.GenerateModelConstantsAction
import com.flowable.atlas.generate.ModelConstantsSettings
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Features 1+2: the Atlas Hub / Tools action must generate the constants class in one click, without
 * a modal prompt, once a class name is configured — the path that is reliable under Remote Dev.
 */
class GenerateModelConstantsHardeningTest : BasePlatformTestCase() {

    fun testGeneratesWithoutPromptUsingConfiguredFqcn() {
        ModelConstantsSettings.getInstance(project).state.fqcn = "gen.Keys"
        myFixture.addFileToProject(
            "models/DEMO-P001.bpmn",
            """<definitions><process id="DEMO-P001" name="Order"/></definitions>""",
        )

        GenerateModelConstantsAction.generate(project)

        assertGeneratedWith("gen/Keys.java", "DEMO-P001")
    }

    /**
     * The index is cold for the whole warm-up scan and again after every model save — exactly when a
     * developer who just added a model asks for the class. The action used to read the cache only, got
     * nothing, and ended in a "NullPointerException" balloon; it waits for the build now. A light test
     * builds inline on any ask, which is how this went unseen, so this test takes the production path.
     */
    fun testGeneratesWhileTheIndexIsCold() {
        ModelConstantsSettings.getInstance(project).state.fqcn = "gen.ColdKeys"
        myFixture.addFileToProject(
            "models/DEMO-P002.bpmn",
            """<definitions><process id="DEMO-P002" name="Order"/></definitions>""",
        )
        val service = project.service<FlowableModelIndexService>()
        service.inlineBuildsInTests = false
        try {
            service.invalidate()
            assertNull("the index is cold when the action runs", service.cachedOrNull())

            GenerateModelConstantsAction.generate(project)

            assertGeneratedWith("gen/ColdKeys.java", "DEMO-P002")
        } finally {
            service.inlineBuildsInTests = true
        }
    }

    private fun assertGeneratedWith(path: String, key: String) {
        val file = ProjectRootManager.getInstance(project).contentSourceRoots
            .firstNotNullOfOrNull { it.findFileByRelativePath(path) }
        assertNotNull("the constants class should be generated without any prompt", file)
        val text = String(file!!.contentsToByteArray(), Charsets.UTF_8)
        assertTrue("generated class should carry the model key, was:\n$text", text.contains(key))
    }
}
