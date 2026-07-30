package com.flowable.atlas.script.documentation

import com.flowable.atlas.script.ScriptContext
import com.flowable.atlas.script.completion.ScriptScope
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Hover on a binding answers from the catalog — never "No candidates found for method call". */
class ScriptBindingsDocumentationProviderTest : BasePlatformTestCase() {

    private val provider = ScriptBindingsDocumentationProvider()

    private fun leafAt(text: String, target: String, context: ScriptContext?): PsiElement {
        val file = myFixture.configureByText("s.groovy", text)
        context?.let { file.putUserData(ScriptScope.CONTEXT_KEY, it) }
        val offset = text.indexOf(target)
        check(offset >= 0)
        return file.findElementAt(offset + 1)!!
    }

    fun testRootDocComesFromTheCatalog() {
        val doc = provider.generateDoc(null,
            leafAt("execution.setVariable('x', 1)", "execution", ScriptContext.BPMN_SCRIPT_TASK))
        assertNotNull(doc)
        assertTrue(doc!!, "DelegateExecution" in doc && "Script task (BPMN)" in doc)
    }

    fun testMemberDocShowsTheSignature() {
        val doc = provider.generateDoc(null,
            leafAt("execution.setTransientVariable('m', 1)", "setTransientVariable",
                ScriptContext.BPMN_SCRIPT_TASK))
        assertNotNull(doc)
        assertTrue(doc!!, "setTransientVariable(variableName, value)" in doc)
    }

    fun testFlwSubObjectMemberDoc() {
        val doc = provider.generateDoc(null,
            leafAt("flw.time.now()", "now", ScriptContext.ACTION_BOT))
        assertNotNull(doc)
        assertTrue(doc!!, "now()" in doc && "flw.time" in doc)
    }

    fun testNoDocOutsideFlowableScriptBodies() {
        assertNull(provider.generateDoc(null,
            leafAt("execution.setVariable('x', 1)", "execution", context = null)))
    }

    fun testLocallyResolvedNamesAreLeftToTheLanguage() {
        val doc = provider.generateDoc(null,
            leafAt("def execution = 1\nexecution.intValue()", "execution.intValue",
                ScriptContext.BPMN_SCRIPT_TASK))
        assertNull("a locally resolved name documents itself", doc)
    }
}
