package com.flowable.atlas

import com.flowable.atlas.action.GenerateModelConstantsAction
import com.flowable.atlas.generate.ModelConstantsSettings
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

        val file = ProjectRootManager.getInstance(project).contentSourceRoots
            .firstNotNullOfOrNull { it.findFileByRelativePath("gen/Keys.java") }
        assertNotNull("the constants class should be generated without any prompt", file)
        val text = String(file!!.contentsToByteArray(), Charsets.UTF_8)
        assertTrue("generated class should carry the model key, was:\n$text", text.contains("DEMO-P001"))
    }
}
