package com.flowable.atlas.script.inspection

import com.flowable.atlas.script.ScriptContext
import com.flowable.atlas.script.completion.ScriptScope
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlText
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The suppressor kills Groovy/JS unresolved-noise exactly inside Flowable script bodies. */
class FlowableScriptInspectionSuppressorTest : BasePlatformTestCase() {

    private val suppressor = FlowableScriptInspectionSuppressor()

    fun testSuppressesInsideInjectedBpmnScriptBody() {
        myFixture.configureByText(
            "task.bpmn20.xml",
            """<?xml version="1.0"?>
               <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                            xmlns:flowable="http://flowable.org/bpmn">
                 <process id="P1">
                   <scriptTask id="s" scriptFormat="groovy">
                     <script>execution.setVariable('x', 1)</script>
                   </scriptTask>
                 </process>
               </definitions>""",
        )
        val host = PsiTreeUtil.findChildrenOfType(myFixture.file, XmlText::class.java)
            .first { it.text.contains("setVariable") }
        val injected = InjectedLanguageManager.getInstance(project)
            .getInjectedPsiFiles(host)!!.map { it.first as PsiFile }.first()
        val element = injected.firstChild
        assertTrue(suppressor.isSuppressedFor(element, "GrUnresolvedAccess"))
        assertTrue(suppressor.isSuppressedFor(element, "GroovyAssignabilityCheck"))
        assertFalse("only the unresolved tool ids are suppressed",
            suppressor.isSuppressedFor(element, "GroovyUnusedDeclaration"))
    }

    fun testSuppressesInThePlaygroundFileButNotInOrdinaryGroovy() {
        val plain = myFixture.configureByText("a.groovy", "execution.setVariable('x', 1)")
        assertFalse(suppressor.isSuppressedFor(plain.firstChild, "GrUnresolvedAccess"))
        plain.putUserData(ScriptScope.CONTEXT_KEY, ScriptContext.BPMN_SCRIPT_TASK)
        assertTrue(suppressor.isSuppressedFor(plain.firstChild, "GrUnresolvedAccess"))
    }

    /** End to end through the real Groovy inspection + the daemon: proves the `language="any"`
     *  registration is honored and the tool id actually matches. */
    fun testRealGroovyUnresolvedInspectionIsSuppressedEndToEnd() {
        val ep = com.intellij.codeInspection.LocalInspectionEP.LOCAL_INSPECTION.extensionList
            .firstOrNull { it.getShortName() == "GrUnresolvedAccess" } ?: return
        myFixture.enableInspections(ep.instantiateTool() as com.intellij.codeInspection.LocalInspectionTool)

        // sanity: in ordinary Groovy the inspection fires on the unresolved binding
        myFixture.configureByText("plain.groovy", "execution.setVariable('x', 1)")
        val plainHits = myFixture.doHighlighting().filter { it.description?.contains("execution") == true }
        assertTrue("expected the unresolved highlight in plain groovy", plainHits.isNotEmpty())

        // in a context-stamped playground file the suppressor stands down the same inspection
        val stamped = myFixture.configureByText("play.groovy", "execution.setVariable('x', 1)")
        stamped.putUserData(ScriptScope.CONTEXT_KEY, ScriptContext.BPMN_SCRIPT_TASK)
        val stampedHits = myFixture.doHighlighting().filter { it.description?.contains("execution") == true }
        assertTrue("expected no unresolved highlight in the playground file, got: " +
            stampedHits.map { it.description }, stampedHits.isEmpty())
    }
}
