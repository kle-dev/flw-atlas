package com.flowable.atlas.icons

import com.flowable.atlas.model.ModelType
import com.flowable.atlas.settings.FlowableAtlasSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Model files get their type's icon; archives an archive; everything else is left to the platform. */
class ModelFileIconProviderTest : BasePlatformTestCase() {

    private val provider = ModelFileIconProvider()

    private fun iconOf(path: String) =
        provider.getIcon(myFixture.addFileToProject(path, "").virtualFile, 0, project)

    fun testDeploymentArtifactsWearTheirTypesIcon() {
        assertSame(AtlasIcons.forType(ModelType.PROCESS), iconOf("models/a.bpmn"))
        assertSame(AtlasIcons.forType(ModelType.PROCESS), iconOf("models/b.bpmn20.xml"))
        assertSame(AtlasIcons.forType(ModelType.CASE), iconOf("models/c.cmmn"))
        assertSame(AtlasIcons.forType(ModelType.FORM), iconOf("models/d.form"))
        assertSame(AtlasIcons.forType(ModelType.APP), iconOf("models/e.app"))
    }

    fun testABarIsAnArchiveAndAZipIsNotOurs() {
        assertSame(AtlasIcons.Archive, iconOf("deploy/DEMO-app.bar"))
        assertNull("a zip may be anything — the platform keeps its icon", iconOf("deploy/DEMO-app.zip"))
        assertNull(iconOf("README.md"))
        assertNull(provider.getIcon(myFixture.tempDirFixture.findOrCreateDir("models"), 0, project))
    }

    fun testDesignWorkspaceJsonFollowsTheIndexSetting() {
        // The icon says "this is a model to Atlas" — so it must agree with what the index treats as one.
        val settings = FlowableAtlasSettings.getInstance()
        val before = settings.indexDesignWorkspace
        try {
            settings.indexDesignWorkspace = false
            assertNull(iconOf("workspace/bpmn-models/x.json"))
            settings.indexDesignWorkspace = true
            assertSame(AtlasIcons.forType(ModelType.PROCESS), iconOf("workspace/bpmn-models/y.json"))
        } finally {
            settings.indexDesignWorkspace = before
        }
    }
}
