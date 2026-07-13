package com.flowable.atlas.parsing

import com.flowable.atlas.graph.Ctx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reference-extraction fixes from the relationship audit, each pinned by an inline fixture:
 * event direction, message/signal correlation, case service task, external-worker topics,
 * calledElementType=id (suspect), expression-only beans, Design form-key casing, CMMN access on
 * generic tasks, DMN decision services + DRD + `inputExpression/<text>`, the action botKey
 * discriminator, and the service/agent/app/policy JSON reference gaps.
 */
class ReferenceFixesTest {

    private fun refs(ctx: Ctx): List<Map<String, Any?>> = ctx.refs

    private fun refTriples(ctx: Ctx): Set<Triple<Any?, Any?, Any?>> =
        ctx.refs.map { Triple(it["rel"], it["kind"], it["value"]) }.toSet()

    // ---------------------------------------------------------------- BPMN

    @Test
    fun eventRegistryDirectionFollowsTheElement() {
        val ctx = Ctx()
        BackendModelParsers.parseBpmn(
            """<definitions xmlns:flowable="http://flowable.org/bpmn">
                 <process id="p1">
                   <startEvent id="s"><extensionElements><flowable:eventType>orderCreated</flowable:eventType></extensionElements></startEvent>
                   <intermediateThrowEvent id="t"><extensionElements><flowable:eventType>orderShipped</flowable:eventType></extensionElements></intermediateThrowEvent>
                   <serviceTask id="se" flowable:type="send-event"><extensionElements><flowable:eventType>orderBilled</flowable:eventType></extensionElements></serviceTask>
                 </process>
               </definitions>""".toByteArray(), ctx, "p1.bpmn",
        )
        val t = refTriples(ctx)
        assertTrue(t.toString(), Triple("receives-event", "event", "orderCreated") in t)
        assertTrue(t.toString(), Triple("sends-event", "event", "orderShipped") in t)
        assertTrue(t.toString(), Triple("sends-event", "event", "orderBilled") in t)
    }

    @Test
    fun messageAndSignalEventsCorrelateByName() {
        val ctx = Ctx()
        BackendModelParsers.parseBpmn(
            """<definitions>
                 <message id="msg1" name="paymentReceived"/>
                 <signal id="sig1" name="cancelAll"/>
                 <process id="p1">
                   <startEvent id="s"><messageEventDefinition messageRef="msg1"/></startEvent>
                   <intermediateThrowEvent id="t"><signalEventDefinition signalRef="sig1"/></intermediateThrowEvent>
                   <receiveTask id="r" messageRef="msg1"/>
                   <extensionElements>
                     <executionListener event="end" signalName="cancelAll"/>
                   </extensionElements>
                 </process>
               </definitions>""".toByteArray(), ctx, "p1.bpmn",
        )
        val t = refTriples(ctx)
        // events reference the definition by id but correlate by NAME
        assertTrue(t.toString(), Triple("catches-message", "message", "paymentReceived") in t)
        assertTrue(t.toString(), Triple("throws-signal", "signal", "cancelAll") in t)
        assertTrue(t.toString(), Triple("catches-message", "message", "paymentReceived") in t)
        // listener throw-events publish by name
        assertTrue(t.toString(), t.any { it.first == "throws-signal" && it.third == "cancelAll" })
    }

    @Test
    fun caseServiceTaskAndExternalWorkerTopic() {
        val ctx = Ctx()
        BackendModelParsers.parseBpmn(
            """<definitions xmlns:flowable="http://flowable.org/bpmn">
                 <process id="p1">
                   <serviceTask id="c" flowable:type="case" flowable:caseDefinitionKey="reviewCase"/>
                   <serviceTask id="w" flowable:type="external-worker" flowable:topic="orders"/>
                 </process>
               </definitions>""".toByteArray(), ctx, "p1.bpmn",
        )
        val t = refTriples(ctx)
        assertTrue(t.toString(), Triple("caseTask", "case", "reviewCase") in t)
        assertTrue(t.toString(), Triple("external-topic", "topic", "orders") in t)
    }

    @Test
    fun calledElementTypeIdIsSuspect() {
        val ctx = Ctx()
        BackendModelParsers.parseBpmn(
            """<definitions xmlns:flowable="http://flowable.org/bpmn">
                 <process id="p1">
                   <callActivity id="a" calledElement="other" flowable:calledElementType="id"/>
                   <callActivity id="b" calledElement="third"/>
                 </process>
               </definitions>""".toByteArray(), ctx, "p1.bpmn",
        )
        val byValue = refs(ctx).filter { it["rel"] == "callActivity" }.associateBy { it["value"] }
        assertEquals(true, byValue["other"]?.get("suspect"))
        assertNull(byValue["third"]?.get("suspect"))
    }

    @Test
    fun expressionOnlyBeanIsScanned() {
        val ctx = Ctx()
        BackendModelParsers.parseBpmn(
            """<definitions xmlns:flowable="http://flowable.org/bpmn">
                 <process id="p1"><serviceTask id="s" flowable:expression="${'$'}{myBean}"/></process>
               </definitions>""".toByteArray(), ctx, "p1.bpmn",
        )
        assertTrue(refTriples(ctx).toString(),
            Triple("serviceTask-delegate", "bean", "myBean") in refTriples(ctx))
    }

    @Test
    fun designFormKeysMatchCaseInsensitively() {
        val ctx = Ctx()
        BackendModelParsers.parseBpmn(
            """<definitions xmlns:flowable="http://flowable.org/bpmn">
                 <process id="p1">
                   <extensionElements><flowable:startFormKey>initForm</flowable:startFormKey></extensionElements>
                 </process>
               </definitions>""".toByteArray(), ctx, "p1.bpmn",
        )
        assertTrue(refTriples(ctx).toString(),
            Triple("start-form", "form", "initForm") in refTriples(ctx))
    }

    // ---------------------------------------------------------------- CMMN

    @Test
    fun cmmnGenericTaskAssignmentRecordsAccess() {
        val ctx = Ctx()
        BackendModelParsers.parseCmmn(
            """<definitions xmlns:flowable="http://flowable.org/cmmn">
                 <case id="c1">
                   <casePlanModel id="plan">
                     <planItem id="pi1" definitionRef="t1"/>
                     <task id="t1" flowable:type="service" flowable:candidateGroups="reviewers"/>
                   </casePlanModel>
                 </case>
               </definitions>""".toByteArray(), ctx, "c1.cmmn",
        )
        val access = ctx.access.firstOrNull { it["scope"] == "task:t1" }
        assertEquals(listOf("reviewers"), access?.get("groups"))
    }

    @Test
    fun cmmnSignalListenerAndChannelKey() {
        val ctx = Ctx()
        BackendModelParsers.parseCmmn(
            """<definitions xmlns:flowable="http://flowable.org/cmmn">
                 <case id="c1">
                   <casePlanModel id="plan">
                     <planItem id="pi1" definitionRef="l1"/>
                     <planItem id="pi2" definitionRef="t1"/>
                     <signalEventListener id="l1" flowable:signalRef="cancelAll"/>
                     <task id="t1" flowable:type="send-event">
                       <extensionElements>
                         <flowable:eventType>orderShipped</flowable:eventType>
                         <flowable:channelKey>ordersOut</flowable:channelKey>
                       </extensionElements>
                     </task>
                   </casePlanModel>
                 </case>
               </definitions>""".toByteArray(), ctx, "c1.cmmn",
        )
        val t = refTriples(ctx)
        assertTrue(t.toString(), Triple("catches-signal", "signal", "cancelAll") in t)
        assertTrue(t.toString(), Triple("sends-event", "event", "orderShipped") in t)
        assertTrue(t.toString(), Triple("via-channel", "channel", "ordersOut") in t)
    }

    // ---------------------------------------------------------------- DMN

    @Test
    fun dmnReadsInputExpressionTextAndDecisionServices() {
        val ctx = Ctx()
        val out = ModelParsers.parseDmn(
            """<definitions>
                 <decision id="d1" name="First">
                   <decisionTable hitPolicy="FIRST">
                     <input><inputExpression id="ie1"><text>orderTotal</text></inputExpression></input>
                     <output name="approved"/>
                     <rule/>
                   </decisionTable>
                   <informationRequirement><requiredDecision href="#d2"/></informationRequirement>
                 </decision>
                 <decision id="d2" name="Second"/>
                 <decisionService id="ds1" name="Svc">
                   <outputDecision href="#d1"/>
                   <encapsulatedDecision href="#d2"/>
                 </decisionService>
               </definitions>""".toByteArray(), ctx, "d.dmn",
        )
        val d1 = out.first { it["key"] == "d1" }
        assertEquals(listOf("orderTotal"), d1["inputs"])
        // decision service is an indexable decision target with its member decisions
        val ds = out.first { it["key"] == "ds1" }
        assertEquals(true, ds["decisionService"])
        assertEquals(listOf("d1", "d2"), ds["decisions"])
        val t = refTriples(ctx)
        assertTrue(t.toString(), Triple("requires", "decision", "d2") in t)
        assertTrue(t.toString(), Triple("contains-decision", "decision", "d1") in t)
    }

    // ---------------------------------------------------------------- platform JSON models

    @Test
    fun actionBotKeyDiscriminatesSignalName() {
        fun parse(botKey: String): Set<Triple<Any?, Any?, Any?>> {
            val ctx = Ctx()
            ModelParsers.parseAction(
                """{"key":"a1","botKey":"$botKey","signalName":"target"}""".toByteArray(), ctx, "a1.action")
            return refTriples(ctx)
        }
        assertTrue(Triple("starts-process", "process", "target") in parse("bpmn-start-process-instance-bot"))
        assertTrue(Triple("starts-case", "case", "target") in parse("cmmn-start-case-instance-bot"))
        assertTrue(Triple("triggers-signal", "signal", "target") in parse("some-other-bot"))
    }

    @Test
    fun serviceModelReferencesDictionariesTemplatesAndDataObject() {
        val ctx = Ctx()
        ModelParsers.parseService(
            """{"key":"svc1","referenceKey":"customerDO",
                "outputParameters":[{"name":"o","typeReference":{"modelKey":"dict1"}}],
                "operations":[{"key":"op1","config":{"method":"POST","url":"/x",
                  "bodyTemplateModel":{"bodyTemplateTemplateModelKey":"tpl1"}},
                  "inputParameters":[{"name":"i","typeReference":{"modelKey":"dict2"}}]}]
               }""".toByteArray(), ctx, "svc1.service",
        )
        val t = refTriples(ctx)
        assertTrue(t.toString(), Triple("service-dataObject", "dataObject", "customerDO") in t)
        assertTrue(t.toString(), Triple("typed-by-dictionary", "dataDictionary", "dict1") in t)
        assertTrue(t.toString(), Triple("typed-by-dictionary", "dataDictionary", "dict2") in t)
        assertTrue(t.toString(), Triple("body-template", "template", "tpl1") in t)
    }

    @Test
    fun agentGuardrailsEvaluatorsAndTemplates() {
        val ctx = Ctx()
        ModelParsers.parseAgent(
            """{"key":"ag1",
                "guardrails":[{"type":"agent","agentModel":{"key":"guardAgent"}},
                              {"type":"service","configuration":{"serviceModel":{"key":"guardSvc"}}}],
                "evaluators":[{"type":"agent","reference":{"key":"evalAgent"}}],
                "operations":[{"key":"op1","behavior":{"type":"freemarkerTemplate",
                  "systemMessageTemplate":{"templateKey":"sysTpl"},
                  "userMessageTemplate":{"templateKey":"usrTpl"}}}],
                "documentClassification":{"behavior":{"type":"freemarkerTemplate",
                  "systemMessageTemplate":{"templateKey":"dcTpl"}},
                  "documentClassifications":[{"contentModel":{"key":"doc1"}}]}
               }""".toByteArray(), ctx, "ag1.agent",
        )
        val t = refTriples(ctx)
        assertTrue(t.toString(), Triple("guardrail", "agent", "guardAgent") in t)
        assertTrue(t.toString(), Triple("guardrail", "service", "guardSvc") in t)
        assertTrue(t.toString(), Triple("evaluator", "agent", "evalAgent") in t)
        assertTrue(t.toString(), Triple("message-template", "template", "sysTpl") in t)
        assertTrue(t.toString(), Triple("message-template", "template", "usrTpl") in t)
        assertTrue(t.toString(), Triple("message-template", "template", "dcTpl") in t)
        assertTrue(t.toString(), Triple("classifies-document", "document", "doc1") in t)
    }

    @Test
    fun appPageModelsBecomeContainsRefs() {
        val ctx = Ctx()
        ModelParsers.parseApp(
            """{"key":"app1","pageModels":[{"key":"page1"}]}""".toByteArray(), ctx, "app1.app")
        assertTrue(refTriples(ctx).toString(),
            Triple("contains", "model:page", "page1") in refTriples(ctx))
    }

    @Test
    fun policyPermissionsRecordGroupAccess() {
        val ctx = Ctx()
        ModelParsers.parsePolicy(
            """{"key":"pol1","permissionMappings":{
                 "read":{"label":"Read","permissionValues":{"managers":true,"clerks":false}}}}""".toByteArray(),
            ctx, "pol1.policy",
        )
        val access = ctx.access.firstOrNull { it["modelType"] == "securityPolicy" }
        assertEquals("read", access?.get("action"))
        assertEquals(listOf("managers"), access?.get("groups"))
        assertFalse(ctx.groups.contains("clerks"))
    }
}
