package com.flowable.atlas

import com.flowable.atlas.completion.ValueKeyMatching
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.navigation.FlowableKeyDocumentationProvider
import com.flowable.atlas.settings.FlowableAtlasSettings
import com.intellij.openapi.components.service
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Opt-in value-based model-key recognition ([ValueKeyMatching]): when enabled, a string literal whose
 * value equals a known model key gets the diagram gutter icon, navigation/Find Usages and hover even
 * with no Flowable API call site; when disabled, behaviour is unchanged. DEMO-* names — repo public.
 */
class ValueKeyRecognitionTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            FlowableAtlasSettings.getInstance().recognizeModelKeysAnywhere = false
        } finally {
            super.tearDown()
        }
    }

    private fun addProcessModel() {
        myFixture.addFileToProject(
            "models/DEMO-onboarding.bpmn20.xml",
            """<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">""" +
                """<process id="DEMO-onboarding" name="DEMO Onboarding"><startEvent id="s"/></process></definitions>""",
        )
        project.service<FlowableModelIndexService>().index()
    }

    fun testPlausibleGuardRejectsBlankAndSingleChar() {
        assertFalse(ValueKeyMatching.plausible(""))
        assertFalse(ValueKeyMatching.plausible(" "))
        assertFalse(ValueKeyMatching.plausible("a"))
        assertTrue(ValueKeyMatching.plausible("ab"))
        assertTrue(ValueKeyMatching.plausible("DEMO-onboarding"))
    }

    fun testDiagramGutterAppearsForValueMatchWhenEnabled() {
        addProcessModel()
        FlowableAtlasSettings.getInstance().recognizeModelKeysAnywhere = true
        // A bare local — NOT a recognised Flowable API call site.
        myFixture.configureByText("A.java", "class A { String k = \"DEMO-onboarding\"; }")
        myFixture.doHighlighting()
        val gutters = myFixture.findAllGutters().filter { it.tooltipText == "Open the Flowable model diagram" }
        assertEquals("value-matched process key should carry the diagram marker", 1, gutters.size)
    }

    fun testDiagramGutterAbsentWhenDisabled() {
        addProcessModel()
        FlowableAtlasSettings.getInstance().recognizeModelKeysAnywhere = false
        myFixture.configureByText("A.java", "class A { String k = \"DEMO-onboarding\"; }")
        myFixture.doHighlighting()
        val gutters = myFixture.findAllGutters().filter { it.tooltipText == "Open the Flowable model diagram" }
        assertTrue("without the opt-in, a non-call-site literal gets no diagram marker", gutters.isEmpty())
    }

    fun testReferenceResolvesForValueMatchWhenEnabled() {
        addProcessModel()
        FlowableAtlasSettings.getInstance().recognizeModelKeysAnywhere = true
        myFixture.configureByText("A.java", "class A { String k = \"DEMO-onb<caret>oarding\"; }")
        val ref = myFixture.getReferenceAtCaretPosition()
        assertNotNull("value-matched key literal should expose a navigable reference", ref)
        assertEquals(
            "the reference should resolve to the model file",
            "DEMO-onboarding.bpmn20.xml",
            (ref!!.resolve() as? PsiFile)?.name,
        )
    }

    fun testNoReferenceWhenDisabled() {
        addProcessModel()
        FlowableAtlasSettings.getInstance().recognizeModelKeysAnywhere = false
        myFixture.configureByText("A.java", "class A { String k = \"DEMO-onb<caret>oarding\"; }")
        assertNull("without the opt-in, a non-call-site literal is not a reference", myFixture.getReferenceAtCaretPosition())
    }

    fun testNoReferenceForUnknownString() {
        addProcessModel()
        FlowableAtlasSettings.getInstance().recognizeModelKeysAnywhere = true
        myFixture.configureByText("A.java", "class A { String k = \"not-a-mo<caret>del-key\"; }")
        assertNull("a string that is not a known key is never a reference", myFixture.getReferenceAtCaretPosition())
    }

    fun testHoverForValueMatchWhenEnabled() {
        addProcessModel()
        FlowableAtlasSettings.getInstance().recognizeModelKeysAnywhere = true
        myFixture.configureByText("A.java", "class A { static final String K = \"DEMO-onboarding\"; }")
        val literal = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiLiteralExpression::class.java)
            .first { it.value == "DEMO-onboarding" }
        val doc = FlowableKeyDocumentationProvider().generateDoc(literal, literal)
        assertNotNull("value-matched key should get hover docs when enabled", doc)
        assertTrue("hover should name the model type: $doc", doc!!.contains("Process"))
    }

    fun testNoHoverForProcessKeyWhenDisabled() {
        addProcessModel()
        FlowableAtlasSettings.getInstance().recognizeModelKeysAnywhere = false
        myFixture.configureByText("A.java", "class A { static final String K = \"DEMO-onboarding\"; }")
        val literal = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiLiteralExpression::class.java)
            .first { it.value == "DEMO-onboarding" }
        assertNull(
            "without the opt-in, a non-DATA_OBJECT key at no call site gets no hover",
            FlowableKeyDocumentationProvider().generateDoc(literal, literal),
        )
    }
}
