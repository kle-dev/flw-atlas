package com.flowable.atlas

import com.flowable.atlas.inspection.FlowableXmlBrokenKeyInspection
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Tests for the v3 improvements:
 *  - infix key completion (`0061` → `KYC-DO-0061`),
 *  - vocabularies scoped to the sibling process/case key (`taskDefinitionKey`),
 *  - model→model key completion / navigation / broken-key inspection inside BPMN/CMMN XML.
 */
class FlowableInfixAndXmlTest : BasePlatformTestCase() {

    private fun addDataObjectQueryStub() {
        myFixture.addFileToProject(
            "com/flowable/dataobject/api/runtime/DataObjectInstanceVariableContainerQuery.java",
            "package com.flowable.dataobject.api.runtime; public interface DataObjectInstanceVariableContainerQuery { " +
                "DataObjectInstanceVariableContainerQuery definitionKey(String key); }",
        )
    }

    private fun addTaskQueryStub() {
        myFixture.addFileToProject(
            "org/flowable/task/api/TaskInfoQuery.java",
            "package org.flowable.task.api; public interface TaskInfoQuery { " +
                "TaskInfoQuery processDefinitionKey(String key); " +
                "TaskInfoQuery taskDefinitionKey(String key); }",
        )
    }

    // ---- infix key completion ----------------------------------------------------------

    fun testInfixFragmentCompletesFullKey() {
        addDataObjectQueryStub()
        myFixture.addFileToProject("models/KYC-DO-0061.data", """{"key":"KYC-DO-0061","name":"KYC data object"}""")
        myFixture.addFileToProject("models/KYC-DO-0099.data", """{"key":"KYC-DO-0099","name":"Other"}""")
        myFixture.configureByText(
            "T.java",
            "class T { void m(com.flowable.dataobject.api.runtime.DataObjectInstanceVariableContainerQuery q) { " +
                "q.definitionKey(\"0061<caret>\"); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings
        if (strings == null) {
            assertTrue("expected KYC-DO-0061 auto-inserted", myFixture.file.text.contains("\"KYC-DO-0061\""))
        } else {
            assertTrue("expected KYC-DO-0061 among $strings", strings.contains("KYC-DO-0061"))
        }
    }

    // ---- scoped vocabulary -------------------------------------------------------------

    private fun addTwoProcessesWithTasks() {
        myFixture.addFileToProject(
            "models/P1.bpmn20.xml",
            """<definitions><process id="DEMO-P001" name="P1"><userTask id="reviewTask"/></process></definitions>""",
        )
        myFixture.addFileToProject(
            "models/P2.bpmn20.xml",
            """<definitions><process id="DEMO-P002" name="P2"><userTask id="approveTask"/></process></definitions>""",
        )
    }

    fun testTaskDefinitionKeyScopedToSiblingProcessKey() {
        addTaskQueryStub(); addTwoProcessesWithTasks()
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.task.api.TaskInfoQuery q) { " +
                "q.processDefinitionKey(\"DEMO-P001\").taskDefinitionKey(\"<caret>\"); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings.orEmpty()
        assertTrue("expected P1's reviewTask among $strings", strings.contains("reviewTask"))
        assertFalse("P2's approveTask must be excluded when scoped to P1: $strings", strings.contains("approveTask"))
    }

    fun testTaskDefinitionKeyFallsBackToProjectWideUnion() {
        addTaskQueryStub(); addTwoProcessesWithTasks()
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.task.api.TaskInfoQuery q) { q.taskDefinitionKey(\"<caret>\"); } }",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings.orEmpty()
        assertTrue("expected reviewTask among $strings", strings.contains("reviewTask"))
        assertTrue("expected approveTask among $strings", strings.contains("approveTask"))
    }

    // ---- XML model→model cross references ----------------------------------------------

    private fun addTargetProcess() {
        myFixture.addFileToProject(
            "models/TARGET.bpmn20.xml",
            """<definitions><process id="DEMO-P100" name="Target"/></definitions>""",
        )
    }

    fun testCalledElementCompletesProcessKeys() {
        addTargetProcess()
        myFixture.configureByText(
            "caller.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn">
                 <process id="CALLER" name="Caller">
                   <callActivity id="ca1" calledElement="<caret>"/>
                 </process>
               </definitions>""",
        )
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings.orEmpty()
        assertTrue("expected process key DEMO-P100 among $strings", strings.contains("DEMO-P100"))
    }

    fun testXmlBrokenKeyFlagsUnknownCalledElement() {
        addTargetProcess()
        myFixture.enableInspections(FlowableXmlBrokenKeyInspection::class.java)
        myFixture.configureByText(
            "caller.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn">
                 <process id="CALLER" name="Caller">
                   <callActivity id="ca1" calledElement="DEMO-P999"/>
                 </process>
               </definitions>""",
        )
        val infos = myFixture.doHighlighting()
        assertTrue("expected unknown-key warning", infos.any { (it.description ?: "").contains("is not a known") })
    }

    fun testXmlBrokenKeyAcceptsKnownCalledElement() {
        addTargetProcess()
        myFixture.enableInspections(FlowableXmlBrokenKeyInspection::class.java)
        myFixture.configureByText(
            "caller.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn">
                 <process id="CALLER" name="Caller">
                   <callActivity id="ca1" calledElement="DEMO-P100"/>
                 </process>
               </definitions>""",
        )
        val infos = myFixture.doHighlighting()
        assertFalse("valid key must not be flagged", infos.any { (it.description ?: "").contains("is not a known") })
    }

    fun testXmlBrokenKeyIgnoresExpressions() {
        addTargetProcess()
        myFixture.enableInspections(FlowableXmlBrokenKeyInspection::class.java)
        myFixture.configureByText(
            "caller.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn">
                 <process id="CALLER" name="Caller">
                   <callActivity id="ca1" calledElement="${'$'}{targetKey}"/>
                 </process>
               </definitions>""",
        )
        val infos = myFixture.doHighlighting()
        assertFalse("an EL expression must not be flagged", infos.any { (it.description ?: "").contains("is not a known") })
    }

    fun testCalledElementReferenceResolvesToModelFile() {
        addTargetProcess()
        myFixture.configureByText(
            "caller.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn">
                 <process id="CALLER" name="Caller">
                   <callActivity id="ca1" calledElement="DEMO-P1<caret>00"/>
                 </process>
               </definitions>""",
        )
        val ref = myFixture.getReferenceAtCaretPosition()
        assertNotNull("expected a Flowable XML key reference", ref)
        val target = ref!!.resolve()
        assertTrue("reference must resolve to the model file", target is PsiFile && target.name == "TARGET.bpmn20.xml")
    }
}
