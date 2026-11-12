package com.flowable.atlas.inspection

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.navigation.FlowableKeyDocumentationProvider
import com.intellij.openapi.components.service
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * A message, signal or task key in Java is a link to where a model declares it, and one no model declares
 * is flagged — except where the verdict would be a guess: a bare `signalRef` (the engine's name for it), an
 * unscoped task key, a name a model computes at run time.
 */
class FlowableUnknownNameInspectionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(FlowableUnknownNameInspection::class.java)
        myFixture.addFileToProject(
            "org/flowable/engine/RuntimeService.java",
            "package org.flowable.engine; public interface RuntimeService { " +
                "Object startProcessInstanceByMessage(String messageName); " +
                "void signalEventReceived(String signalName); }",
        )
        myFixture.addFileToProject(
            "org/flowable/task/api/TaskInfoQuery.java",
            "package org.flowable.task.api; public interface TaskInfoQuery { " +
                "TaskInfoQuery processDefinitionKey(String key); TaskInfoQuery taskDefinitionKey(String key); " +
                "TaskInfoQuery taskDefinitionKeyLike(String key); TaskInfoQuery or(); }",
        )
        myFixture.addFileToProject(
            "models/DEMO-P001.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn">
                 <message id="m1" name="DEMO-msg"/>
                 <signal id="s1" name="DEMO-sig"/>
                 <process id="DEMO-P001" name="Order">
                   <userTask id="DEMO-task"/>
                   <intermediateCatchEvent id="c1"><signalEventDefinition signalRef="s1"/></intermediateCatchEvent>
                 </process>
               </definitions>""",
        )
        myFixture.addFileToProject(
            "models/DEMO-P002.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn">
                 <process id="DEMO-P002" name="Billing">
                   <intermediateCatchEvent id="c2"><signalEventDefinition signalRef="DEMO-bare"/></intermediateCatchEvent>
                 </process>
               </definitions>""",
        )
        project.service<FlowableModelIndexService>().index()
    }

    private fun java(body: String) = myFixture.configureByText(
        "DemoCaller.java",
        """class DemoCaller { void m(org.flowable.engine.RuntimeService rs, org.flowable.task.api.TaskInfoQuery q) { $body } }""",
    )

    private fun warnings() = myFixture.doHighlighting().mapNotNull { it.description }.filter { it.contains("is not a known") }

    fun testASignalResolvesToItsDeclaration() {
        java("""rs.signalEventReceived("DEMO-<caret>sig");""")
        val target = myFixture.getReferenceAtCaretPosition()!!.resolve()
        assertEquals("DEMO-P001.bpmn20.xml", target?.containingFile?.name)
        val at = target!!.textRange.startOffset
        assertTrue("on the signal's declaration", target.containingFile.text.substring(at).startsWith("DEMO-sig"))
        assertTrue(warnings().isEmpty())
    }

    fun testTheHoverNamesTheModelThatDeclaresIt() {
        java("""rs.signalEventReceived("DEMO-<caret>sig");""")
        val literal = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), PsiLiteralExpression::class.java)!!
        val provider = FlowableKeyDocumentationProvider()
        assertEquals("<b>DEMO-sig</b> — Signal · in DEMO-P001", provider.getQuickNavigateInfo(literal, literal))
        assertTrue(provider.generateDoc(literal, literal)!!.contains("DEMO-P001.bpmn20.xml"))
    }

    fun testATaskKeyResolvesInsideTheModelTheChainNames() {
        java("""q.processDefinitionKey("DEMO-P001").taskDefinitionKey("DEMO-<caret>task");""")
        assertEquals("DEMO-P001.bpmn20.xml", myFixture.getReferenceAtCaretPosition()!!.resolve()?.containingFile?.name)
    }

    fun testAMisspeltMessageIsFlaggedWithAFix() {
        java("""rs.startProcessInstanceByMessage("DEMO-ms<caret>gg");""")
        assertEquals(listOf("'DEMO-msgg' is not a known message — did you mean 'DEMO-msg'?"), warnings())
        myFixture.launchAction(myFixture.findSingleIntention("Replace with 'DEMO-msg'"))
        assertTrue(myFixture.editor.document.text.contains("\"DEMO-msg\""))
    }

    fun testABareSignalRefIsTheEnginesName() {
        java("""rs.signalEventReceived("DEMO-bare");""")
        assertTrue("a signalRef naming no <signal> is the signal's name at run time", warnings().isEmpty())
    }

    fun testASignalACaseListensForIsKnown() {
        myFixture.addFileToProject(
            "models/DEMO-C001.cmmn.xml",
            """<definitions xmlns:flowable="http://flowable.org/cmmn">
                 <case id="DEMO-C001" name="Review"><casePlanModel id="plan">
                   <planItem id="pi1" definitionRef="wait"/>
                   <eventListener id="wait" flowable:eventListenerType="signal" flowable:signalRef="DEMO-case-sig"/>
                 </casePlanModel></case>
               </definitions>""",
        )
        project.service<FlowableModelIndexService>().index()
        java("""rs.signalEventReceived("DEMO-case-<caret>sig");""")
        assertTrue("the engine's signal reaches a case's signal event listener too", warnings().isEmpty())
        assertEquals("DEMO-C001.cmmn.xml", myFixture.getReferenceAtCaretPosition()!!.resolve()?.containingFile?.name)
    }

    fun testATaskKeyIsJudgedOnlyAgainstTheModelTheChainNames() {
        java("""q.processDefinitionKey("DEMO-P001").taskDefinitionKey("DEMO-tsak");""")
        assertEquals(listOf("'DEMO-tsak' is not a known task key of DEMO-P001 — did you mean 'DEMO-task'?"), warnings())
        java("""q.taskDefinitionKey("DEMO-tsak");""")
        assertTrue("unscoped, a task of a process outside the project is a fine thing to ask for", warnings().isEmpty())
        java("""q.processDefinitionKey("DEMO-P001").taskDefinitionKeyLike("DEMO-ts%");""")
        assertTrue("a pattern is no name", warnings().isEmpty())
        java("""q.or().processDefinitionKey("DEMO-P001").taskDefinitionKey("DEMO-tsak");""")
        assertTrue("with or() the key does not narrow the task", warnings().isEmpty())
    }

    fun testNoVerdictOnSignalsWhenAModelComputesOne() {
        myFixture.addFileToProject(
            "models/DEMO-P003.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn">
                 <process id="DEMO-P003" name="Dynamic">
                   <intermediateThrowEvent id="t"><signalEventDefinition flowable:signalExpression="${'$'}{signalName}"/></intermediateThrowEvent>
                 </process>
               </definitions>""",
        )
        project.service<FlowableModelIndexService>().index()
        java("""rs.signalEventReceived("DEMO-sgi");""")
        assertTrue(warnings().isEmpty())
    }

    fun testATestSourceIsLeftAlone() {
        val file = myFixture.addFileToProject(
            "src/test/java/DemoCallerTest.java",
            """class DemoCallerTest { void m(org.flowable.engine.RuntimeService rs) { rs.signalEventReceived("DEMO-nobody"); } }""",
        )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        assertTrue(warnings().isEmpty())
    }
}
