package com.flowable.atlas.schema

import com.intellij.javaee.ExternalResourceManager
import com.intellij.javaee.ExternalResourceManagerEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The schemas actually arrive in the IDE, and a realistic export stays quiet.
 *
 * The second half is the one that decides whether this feature may ship at all. Registering a schema
 * turns on the platform's Xerces validation inspection, at error level, in every reader's editor — so a
 * Design export, which carries `design:` attributes no schema describes and Flowable service-task types
 * the open-source enumeration never listed, has to come out clean. Measured on a real corpus that is
 * 99 % of files; this states the same question in the IDE's own terms rather than a validator's.
 */
class FlowableSchemaRegistrationTest : BasePlatformTestCase() {

    fun testEveryNamespaceResolvesToABundledFile() {
        val manager = ExternalResourceManager.getInstance()
        val unresolved = FlowableSchemas.SCHEMAS.map { it.first }.filter { url ->
            // The manager answers with the URL itself when it knows no resource for it.
            manager.getResourceLocation(url) == url
        }
        assertEquals("a namespace nothing resolves leaves the editor as it was", emptyList<String>(), unresolved)
    }

    fun testTheUndescribedFlowableNamespacesAreIgnoredRatherThanUnknown() {
        val manager = ExternalResourceManagerEx.getInstanceEx()
        for (namespace in FlowableSchemas.IGNORED) {
            assertTrue(
                "$namespace has no schema anywhere, so it is ignored on purpose rather than reported",
                manager.isIgnoredResource(namespace),
            )
        }
    }

    fun testARealisticDesignExportHighlightsClean() {
        myFixture.configureByText(
            "DEMO-P001.bpmn20.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         targetNamespace="http://flowable.org/demo"
                         xmlns:flowable="http://flowable.org/bpmn"
                         xmlns:design="http://flowable.org/design"
                         xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                         xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC"
                         design:palette="core">
              <process id="DEMO-P001" name="Onboarding" design:processdefinitionkey="DEMO-P001">
                <extensionElements>
                  <design:stencilid><![CDATA[BPMNDiagram]]></design:stencilid>
                </extensionElements>
                <startEvent id="start"/>
                <!-- A platform service-task type the open-source enumeration does not list. -->
                <serviceTask id="lookup" name="Look up" flowable:type="service-registry" flowable:async="true">
                  <extensionElements>
                    <flowable:executionListener event="end" delegateExpression="${'$'}{auditListener}"/>
                  </extensionElements>
                </serviceTask>
                <sequenceFlow id="f1" sourceRef="start" targetRef="lookup"/>
              </process>
            </definitions>
            """.trimIndent(),
        )
        // No errors and no warnings: a warning here would be the "URI is not registered" line that the
        // ignored namespaces exist to remove.
        myFixture.checkHighlighting(true, false, false)
    }

    fun testACaseModelWithFlowableAttributesHighlightsClean() {
        myFixture.configureByText(
            "DEMO-C001.cmmn",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
                         targetNamespace="http://flowable.org/demo"
                         xmlns:flowable="http://flowable.org/cmmn"
                         xmlns:design="http://flowable.org/design">
              <case id="DEMO-C001" name="Review">
                <casePlanModel id="cpm" name="Review">
                  <planItem id="pi_task" definitionRef="reviewTask"/>
                  <humanTask id="reviewTask" name="Review" flowable:candidateGroups="auditors"/>
                </casePlanModel>
              </case>
            </definitions>
            """.trimIndent(),
        )
        // http://flowable.org/cmmn has no schema anywhere — the point is that this is quiet anyway,
        // because an undescribed namespace is skipped by the validator, not rejected.
        myFixture.checkHighlighting(true, false, false)
    }
}
