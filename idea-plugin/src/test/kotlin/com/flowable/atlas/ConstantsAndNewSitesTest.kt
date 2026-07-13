package com.flowable.atlas

import com.flowable.atlas.inspection.FlowableBrokenKeyInspection
import com.flowable.atlas.inspection.FlowableXmlBrokenKeyInspection
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Functional tests for the audit round: broken-key validation on CONSTANT references (the
 * generated model-constants pattern), form-outcome completion at `completeTaskWithForm`, and the
 * new XML sites — extension-element text (`<flowable:eventType>`) completion + validation and
 * event payload-field completion resolved from the sibling event type.
 */
class ConstantsAndNewSitesTest : BasePlatformTestCase() {

    private fun addCaseStub() {
        myFixture.addFileToProject(
            "org/flowable/cmmn/api/runtime/CaseInstanceBuilder.java",
            "package org.flowable.cmmn.api.runtime; public interface CaseInstanceBuilder { CaseInstanceBuilder caseDefinitionKey(String key); }",
        )
        myFixture.addFileToProject("models/DEMO-C001.cmmn", """<definitions><case id="DEMO-C001" name="C"/></definitions>""")
    }

    fun testConstantBrokenKeyIsFlagged() {
        addCaseStub()
        myFixture.enableInspections(FlowableBrokenKeyInspection::class.java)
        myFixture.addFileToProject(
            "demo/ModelConstants.java",
            "package demo; public final class ModelConstants { public static final String REVIEW = \"DEMO-C999\"; }",
        )
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.cmmn.api.runtime.CaseInstanceBuilder b) { b.caseDefinitionKey(demo.ModelConstants.REVIEW); } }",
        )
        val infos = myFixture.doHighlighting()
        assertTrue("a constant carrying an unknown key must be flagged",
            infos.any { (it.description ?: "").contains("is not a known") })
    }

    fun testConstantKnownKeyIsAccepted() {
        addCaseStub()
        myFixture.enableInspections(FlowableBrokenKeyInspection::class.java)
        myFixture.addFileToProject(
            "demo/ModelConstants.java",
            "package demo; public final class ModelConstants { public static final String REVIEW = \"DEMO-C001\"; }",
        )
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.cmmn.api.runtime.CaseInstanceBuilder b) { b.caseDefinitionKey(demo.ModelConstants.REVIEW); } }",
        )
        val infos = myFixture.doHighlighting()
        assertFalse("a constant carrying a known key must not be flagged",
            infos.any { (it.description ?: "").contains("is not a known") })
    }

    fun testFormOutcomeCompletion() {
        myFixture.addFileToProject(
            "org/flowable/engine/TaskService.java",
            "package org.flowable.engine; public interface TaskService { " +
                "void completeTaskWithForm(String taskId, String formDefinitionId, String outcome, java.util.Map<String, Object> variables); }",
        )
        myFixture.addFileToProject(
            "models/approval.form",
            """{"metadata":{"key":"approvalForm","name":"Approval"},
                "outcomes":[{"value":"approve","label":"Approve"},{"value":"reject","label":"Reject"}],
                "rows":[]}""",
        )
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.engine.TaskService ts) { ts.completeTaskWithForm(\"t1\", \"f1\", \"<caret>\", null); } }",
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings.orEmpty()
        assertTrue("expected form outcomes, got $lookups", lookups.containsAll(listOf("approve", "reject")))
    }

    fun testEventTypeElementTextCompletion() {
        myFixture.addFileToProject("models/orderCreated.event", """{"key":"orderCreated","name":"Order created"}""")
        myFixture.configureByText(
            "proc.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn">
                 <process id="p1">
                   <startEvent id="s"><extensionElements><flowable:eventType><caret></flowable:eventType></extensionElements></startEvent>
                 </process>
               </definitions>""",
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings.orEmpty()
        assertTrue("expected event keys in element-text completion, got $lookups", "orderCreated" in lookups)
    }

    fun testEventTypeElementTextValidation() {
        myFixture.addFileToProject("models/orderCreated.event", """{"key":"orderCreated","name":"Order created"}""")
        myFixture.enableInspections(FlowableXmlBrokenKeyInspection::class.java)
        myFixture.configureByText(
            "proc.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn">
                 <process id="p1">
                   <startEvent id="s"><extensionElements><flowable:eventType>orderCreatd</flowable:eventType></extensionElements></startEvent>
                 </process>
               </definitions>""",
        )
        val infos = myFixture.doHighlighting()
        assertTrue("a mistyped event key in element text must be flagged",
            infos.any { (it.description ?: "").contains("is not a known") })
    }

    fun testEventPayloadCompletionFromSiblingEventType() {
        myFixture.addFileToProject(
            "models/orderCreated.event",
            """{"key":"orderCreated","payload":[{"name":"orderId"},{"name":"customerId"}]}""",
        )
        myFixture.configureByText(
            "proc.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn">
                 <process id="p1">
                   <startEvent id="s">
                     <extensionElements>
                       <flowable:eventType>orderCreated</flowable:eventType>
                       <flowable:eventOutParameter source="<caret>" target="orderIdVar"/>
                     </extensionElements>
                   </startEvent>
                 </process>
               </definitions>""",
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings.orEmpty()
        assertTrue("expected event payload names, got $lookups", lookups.containsAll(listOf("orderId", "customerId")))
    }
}
