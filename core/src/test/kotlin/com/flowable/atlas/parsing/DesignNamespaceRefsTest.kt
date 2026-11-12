package com.flowable.atlas.parsing

import com.flowable.atlas.graph.Ctx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design writes some references and variable names in its own namespace — `design:securitypolicy`,
 * `design:processdefinitionkey`, an import's `design:variablemapping` — and a send-event task's
 * correlation in `flowable:eventCorrelationParameter`. All of them were text nothing read.
 */
class DesignNamespaceRefsTest {

    private fun refs(ctx: Ctx) = ctx.refs.map { Triple(it["rel"], it["kind"], it["value"]) }.toSet()

    @Test
    fun designNamespaceElementsAreReferencesAndVariableWrites() {
        val ctx = Ctx()
        val xml = """<definitions xmlns:flowable="http://flowable.org/bpmn" xmlns:design="http://flowable.org/design">
             <process id="p1">
               <userTask id="t" name="Review">
                 <extensionElements>
                   <design:securitypolicy><![CDATA[DEMO-SP001]]></design:securitypolicy>
                 </extensionElements>
               </userTask>
               <serviceTask id="start" flowable:type="init-variables" flowable:delegateExpression="${'$'}{initVariablesService}">
                 <extensionElements>
                   <design:processdefinitionkey><![CDATA[DEMO-P002]]></design:processdefinitionkey>
                   <design:casedefinitionkey><![CDATA[DEMO-C001]]></design:casedefinitionkey>
                   <design:additionalvariables additionalVariableName="importType" additionalVariableValue="${'$'}{importType}"/>
                   <design:variablemapping columnName="Year" type="integer" variableName="year"/>
                   <design:importreportvariablename><![CDATA[report]]></design:importreportvariablename>
                 </extensionElements>
               </serviceTask>
               <startEvent id="s">
                 <extensionElements>
                   <design:inboundchannelreference><![CDATA[DEMO-CH001]]></design:inboundchannelreference>
                 </extensionElements>
               </startEvent>
             </process>
           </definitions>"""
        BackendModelParsers.parseBpmn(xml.toByteArray(), ctx, "p1.bpmn")
        val r = refs(ctx)
        assertTrue(r.toString(), Triple("security-policy-model", "securityPolicy", "DEMO-SP001") in r)
        assertTrue(r.toString(), Triple("starts-process", "process", "DEMO-P002") in r)
        assertTrue(r.toString(), Triple("starts-case", "case", "DEMO-C001") in r)
        assertTrue(r.toString(), Triple("via-channel", "channel", "DEMO-CH001") in r)
        // the raw-text harvest is what the extractor runs; call it the way the extractor does
        VarHarvest.collectDeclaredVars(ctx, xml, listOf("p1"))
        VarHarvest.collectDirectedVars(ctx, xml, listOf("p1"))
        val writes = ctx.varSites.filter { it["dir"] == Ctx.WRITE }.map { it["variable"].toString() + "|" + it["via"] }.toSet()
        assertTrue(writes.toString(), "importType|additionalVariable" in writes)
        assertTrue(writes.toString(), "year|importColumn" in writes)
        assertTrue(writes.toString(), "report|designOutput" in writes)
    }

    /**
     * Design writes a case's policy on its plan model and a task's policy and SLA on the task — the
     * elements the platform reads them from. Only `<process>` and `<case>` were read, so none of these was
     * a reference and a key that names nothing could not be reported missing.
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun policiesAndSlasAreReadWhereDesignWritesThem() {
        val cctx = Ctx()
        val case = BackendModelParsers.parseCmmn("""<definitions xmlns:flowable="http://flowable.org/cmmn">
             <case id="DEMO-C1">
               <casePlanModel id="plan">
                 <extensionElements>
                   <flowable:security-policy-model><![CDATA[DEMO-casePolicy]]></flowable:security-policy-model>
                 </extensionElements>
                 <planItem id="pi1" definitionRef="ht"/>
                 <humanTask id="ht">
                   <extensionElements>
                     <flowable:security-policy-model><![CDATA[DEMO-taskPolicy]]></flowable:security-policy-model>
                     <flowable:sla-definition-key><![CDATA[DEMO-taskSla]]></flowable:sla-definition-key>
                   </extensionElements>
                 </humanTask>
               </casePlanModel>
             </case>
           </definitions>""".toByteArray(), cctx, "c.cmmn")[0]
        assertTrue(refs(cctx).containsAll(setOf(
            Triple("security-policy-model", "securityPolicy", "DEMO-casePolicy"),
            Triple("security-policy-model", "securityPolicy", "DEMO-taskPolicy"),
            Triple("sla-definition-key", "sla", "DEMO-taskSla"),
        )))
        assertTrue(cctx.refs.all { it["from"] == "DEMO-C1" })
        // the plan model's policy is the case's own, and its page says so
        assertEquals(listOf(mapOf("rel" to "security-policy-model", "key" to "DEMO-casePolicy")), case["modelRefs"])
        val ht = ((case["planModel"] as Map<String, Any?>)["children"] as List<Map<String, Any?>>).single()
        assertEquals("DEMO-taskSla", ht["sla"])
        assertEquals("DEMO-taskPolicy", ht["securityPolicy"])

        val pctx = Ctx()
        val proc = BackendModelParsers.parseBpmn("""<definitions xmlns:flowable="http://flowable.org/bpmn">
             <process id="DEMO-P1">
               <extensionElements>
                 <flowable:security-policy-model><![CDATA[DEMO-procPolicy]]></flowable:security-policy-model>
               </extensionElements>
               <userTask id="ut">
                 <extensionElements>
                   <flowable:security-policy-model><![CDATA[DEMO-taskPolicy]]></flowable:security-policy-model>
                   <flowable:sla-definition-key><![CDATA[DEMO-taskSla]]></flowable:sla-definition-key>
                 </extensionElements>
               </userTask>
             </process>
           </definitions>""".toByteArray(), pctx, "p.bpmn")[0]
        val ut = (proc["userTasks"] as List<Map<String, Any?>>).single()
        assertEquals("DEMO-taskSla", ut["sla"])
        assertEquals("DEMO-taskPolicy", ut["securityPolicy"])
        assertTrue(refs(pctx).containsAll(setOf(
            Triple("security-policy-model", "securityPolicy", "DEMO-taskPolicy"),
            Triple("sla-definition-key", "sla", "DEMO-taskSla"),
        )))
        // the process's own policy is read twice (its model refs, and the element walk) — one reference
        val procPolicy = pctx.refs.filter { it["value"] == "DEMO-procPolicy" }
            .map { listOf(it["fromType"], it["from"], it["rel"], it["kind"], it["value"]) }.distinct()
        assertEquals(1, procPolicy.size)
    }

    @Test
    fun correlationParametersArePayloadAndTagsMatchCaseInsensitively() {
        val ctx = Ctx()
        val proc = BackendModelParsers.parseBpmn(
            """<definitions xmlns:flowable="http://flowable.org/bpmn">
                 <process id="p1">
                   <serviceTask id="send" flowable:type="send-event">
                     <extensionElements>
                       <flowable:eventType><![CDATA[orderPlaced]]></flowable:eventType>
                       <flowable:eventInParameter source="orderId" target="id"/>
                       <flowable:eventCorrelationParameter name="caseId" value="${'$'}{root.id}"/>
                       <flowable:inputparameter name="lowerCased" value="${'$'}{v}"/>
                     </extensionElements>
                   </serviceTask>
                 </process>
               </definitions>""".toByteArray(), ctx, "p1.bpmn",
        )[0]
        @Suppress("UNCHECKED_CAST")
        val io = proc["ioParameters"] as List<Map<String, Any?>>
        val kinds = io.map { it["kind"].toString() + ":" + it["target"] }.toSet()
        assertTrue(kinds.toString(), "eventCorrelationParameter:caseId" in kinds)
        assertTrue(kinds.toString(), "inputParameter:lowerCased" in kinds)
        assertTrue(kinds.toString(), "eventInParameter:id" in kinds)
        // the correlation parameter's name is the event's contract, not a variable of the process
        assertEquals(emptyList<Any?>(), ctx.varSites.filter { it["variable"] == "caseId" })
    }
}
