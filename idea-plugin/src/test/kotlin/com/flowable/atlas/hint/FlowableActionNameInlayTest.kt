package com.flowable.atlas.hint

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.settings.FlowableAtlasSettings
import com.intellij.openapi.components.service
import com.intellij.testFramework.utils.inlays.declarative.DeclarativeInlayHintsProviderTestCase

/**
 * The action-name inline hint: a string literal holding an indexed `.action` key is labelled with the
 * action's name, so an opaque `DEMO-Annn` constant reads as what it starts. DEMO-* names — repo public.
 */
class FlowableActionNameInlayTest : DeclarativeInlayHintsProviderTestCase() {

    override fun tearDown() {
        try {
            FlowableAtlasSettings.getInstance().showActionNameInlay = true
        } finally {
            super.tearDown()
        }
    }

    private fun addActions() {
        myFixture.addFileToProject(
            "models/support.action",
            """{ "key": "DEMO-A033", "name": "Create support request" }""",
        )
        // A nameless action is indexed with its key as the name — it must NOT get a hint that just
        // repeats the literal.
        myFixture.addFileToProject("models/plain.action", """{ "key": "DEMO-A034" }""")
        project.service<FlowableModelIndexService>().index()
    }

    fun testActionNamesMapSkipsKeyEqualNames() {
        addActions()
        val names = FlowableActionNameInlayProvider.actionNames(
            project.service<FlowableModelIndexService>().index(),
        )
        assertEquals(mapOf("DEMO-A033" to "Create support request"), names)
    }

    fun testActionKeyLiteralIsLabelledWithTheActionName() {
        addActions()
        doTestProvider(
            "ModelConstants.java",
            """
            class ModelConstants {
                static final String SUPPORT = "DEMO-A033"/*<# Create support request #>*/;
                static final String PLAIN = "DEMO-A034";
                static final String NOT_A_KEY = "DEMO-A099";
            }
            """.trimIndent(),
            FlowableActionNameInlayProvider(),
            testMode = ProviderTestMode.SIMPLE,
        )
    }

    fun testNoHintWhenSettingIsOff() {
        addActions()
        FlowableAtlasSettings.getInstance().showActionNameInlay = false
        doTestProvider(
            "ModelConstants.java",
            """
            class ModelConstants {
                static final String SUPPORT = "DEMO-A033";
            }
            """.trimIndent(),
            FlowableActionNameInlayProvider(),
            testMode = ProviderTestMode.SIMPLE,
        )
    }
}
