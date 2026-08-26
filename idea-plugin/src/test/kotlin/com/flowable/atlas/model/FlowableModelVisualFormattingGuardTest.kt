package com.flowable.atlas.model

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.actions.ReaderModeProvider
import com.intellij.formatting.visualLayer.VisualFormattingLayerService
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [FlowableModelVisualFormattingGuard] — the guard that keeps IntelliJ 2026.x's visual formatting layer
 * away from Flowable model files, where it throws. DEMO-* names: the repo is public.
 */
class FlowableModelVisualFormattingGuardTest : BasePlatformTestCase() {

    private val guard = FlowableModelVisualFormattingGuard()

    /** As the platform's own reader-mode provider does it, so the guard has something to turn off. */
    private fun enableLayer() =
        VisualFormattingLayerService.enableForEditor(myFixture.editor, CodeStyle.getSettings(myFixture.file))

    private fun applyGuard() = guard.applyModeChanged(project, myFixture.editor, true, false)

    fun testTheLayerIsTurnedOffForAModelFile() {
        // A minified single-line form model: what Design exports, and what the platform crashes on.
        myFixture.configureByText(
            "DEMO-onboarding.form",
            """{"key":"DEMO-onboarding","name":"Onboarding","fields":[{"id":"a"},{"id":"b"}]}""",
        )
        enableLayer()
        assertTrue("precondition: the platform layer is on", VisualFormattingLayerService.isEnabledForEditor(myFixture.editor))

        applyGuard()

        assertFalse(
            "the guard must turn the layer off for a model file",
            VisualFormattingLayerService.isEnabledForEditor(myFixture.editor),
        )
        // The point of the guard: the pass that used to throw here now has nothing to collect. Without
        // the guard this call is what raises "Wrong line: 1. Available lines count: 1".
        assertTrue(
            VisualFormattingLayerService.getInstance()
                .collectVisualFormattingLayerElements(myFixture.editor).isEmpty(),
        )
    }

    fun testAnXmlModelIsCoveredToo() {
        myFixture.configureByText(
            "DEMO-onboarding.bpmn",
            """<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"><process id="DEMO-onboarding"/></definitions>""",
        )
        enableLayer()
        applyGuard()
        assertFalse(VisualFormattingLayerService.isEnabledForEditor(myFixture.editor))
    }

    fun testAFileThatIsNotAModelIsLeftAlone() {
        myFixture.configureByText("A.java", "class A { void f() {} }")
        enableLayer()
        applyGuard()
        assertTrue(
            "a non-model file is none of the guard's business",
            VisualFormattingLayerService.isEnabledForEditor(myFixture.editor),
        )
    }

    /**
     * The ordering is the whole guard: the platform *enables* the layer from this same extension point,
     * so running before it would be pointless. Nothing else in the build would notice this breaking.
     */
    fun testTheGuardRunsAfterThePlatformProviderThatEnablesTheLayer() {
        val providers = ExtensionPointName<ReaderModeProvider>("com.intellij.readerModeProvider")
            .extensionList.map { it.javaClass.name }
        val ours = providers.indexOf(FlowableModelVisualFormattingGuard::class.java.name)
        val platform = providers.indexOfFirst { it.endsWith("VisualFormattingLayerReaderModeProvider") }
        assertTrue("the guard is not registered at all: $providers", ours >= 0)
        assertTrue("the platform provider is gone — re-check whether the guard is still needed: $providers", platform >= 0)
        assertTrue("the guard must run after the platform provider: $providers", ours > platform)
    }
}
