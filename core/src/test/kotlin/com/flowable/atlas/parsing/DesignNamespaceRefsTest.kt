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
