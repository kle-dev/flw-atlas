package com.flowable.atlas.parsing

import com.flowable.atlas.graph.Ctx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the in/out parameter extraction — the one place that has to know all ~10 ways Flowable
 * spells "pass this value in / take that value out".
 *
 * Each case is a minimal inline model rather than a fixture file, so the exhaustive tag matrix lives
 * here instead of bloating `miniproject` and its goldens. The assertions pin the normalised
 * `dir | kind | source -> target` record, because that normalisation is the contract every consumer
 * (overview renderer, graph variable flows, explorer detail view and search) relies on.
 */
class IoParametersTest {

    @Suppress("UNCHECKED_CAST")
    private fun params(model: Map<String, Any?>): List<Map<String, Any?>> =
        model["ioParameters"] as List<Map<String, Any?>>

    /** `"dir|kind|source->target"` plus any optional flags, for compact comparison. */
    private fun sig(p: Map<String, Any?>): String {
        val flags = listOf("type", "transient", "expression").mapNotNull {
            if (p.containsKey(it)) "$it=${p[it]}" else null
        }
        return "${p["dir"]}|${p["kind"]}|${p["source"]}->${p["target"]}" +
            (if (flags.isEmpty()) "" else " [" + flags.joinToString(",") + "]")
    }

    private fun bpmn(body: String): Pair<Map<String, Any?>, Ctx> {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn">
              <process id="p" name="P">$body</process>
            </definitions>"""
        val ctx = Ctx()
        return BackendModelParsers.parseBpmn(xml.toByteArray(), ctx, "p.bpmn")[0] to ctx
    }

    @Test
    fun callActivityInOutKeepsExpressionAndTransientApart() {
        val (p, _) = bpmn(
            """<callActivity id="callSub" name="Fulfil" calledElement="sub">
                 <extensionElements>
                   <flowable:in source="orderId" target="subOrderId"/>
                   <flowable:in sourceExpression="${'$'}{customer.id}" target="custId"/>
                   <flowable:out source="subTotal" target="total" transient="true"/>
                 </extensionElements>
               </callActivity>"""
        )
        assertEquals(
            listOf(
                "in|in|orderId->subOrderId",
                "in|in|\${customer.id}->custId [expression=true]",
                "out|out|subTotal->total [transient=true]",
            ),
            params(p).map { sig(it) },
        )
        // element attribution travels with every record — that is what makes one flat list enough
        assertTrue(params(p).all { it["element"] == "callSub" && it["elementName"] == "Fulfil" })
        assertEquals(setOf("callActivity"), params(p).map { it["elementType"] }.toSet())
    }

    @Test
    fun serviceRegistryTaskParametersAndCallee() {
        val (p, ctx) = bpmn(
            """<serviceTask id="svc" name="Lookup" flowable:type="service-registry">
                 <extensionElements>
                   <flowable:serviceMapping serviceModelKey="custSvc" operationKey="findById"/>
                   <flowable:inputParameter name="customerName" value="${'$'}{custVar}"/>
                   <flowable:outputParameter name="id" value="newCustomerId"/>
                   <flowable:errorOutputParameter name="errorCode" value="ecVar"/>
                   <flowable:outputVariableName><![CDATA[customer]]></flowable:outputVariableName>
                 </extensionElements>
               </serviceTask>"""
        )
        assertEquals(
            listOf(
                "in|inputParameter|\${custVar}->customerName [expression=true]",
                "out|outputParameter|id->newCustomerId",
                "error-out|errorOutputParameter|errorCode->ecVar",
                "out|outputVariableName|null->customer",
            ),
            params(p).map { sig(it) },
        )
        // `flowable:type` tells the reader which integration the mapping feeds
        assertEquals(setOf("service-registry"), params(p).map { it["elementSubType"] }.toSet())

        // BPMN used to read no serviceMapping at all, leaving these parameters without a callee
        @Suppress("UNCHECKED_CAST")
        val task = (p["serviceTasks"] as List<Map<String, Any?>>)[0]
        assertEquals("custSvc", task["serviceModelKey"])
        assertEquals("findById", task["operationKey"])
        assertTrue(ctx.refs.any { it["rel"] == "serviceMapping" && it["value"] == "custSvc" })
        assertTrue(ctx.opUse.any { it["targetKey"] == "custSvc" && it["op"] == "findById" })
    }

    @Test
    fun agentTaskAndDataObjectTaskCallees() {
        val (p, ctx) = bpmn(
            """<serviceTask id="ai" name="AI" flowable:type="agent">
                 <extensionElements>
                   <flowable:agentMapping agentModelKey="itAgent" operationKey="default"/>
                   <flowable:inputParameter name="city" value="${'$'}{city}"/>
                   <flowable:outputParameter name="text" value="answer"/>
                 </extensionElements>
               </serviceTask>
               <serviceTask id="lookup" flowable:delegateExpression="${'$'}{dataObjectServiceTask}">
                 <extensionElements>
                   <flowable:dataObjectMapping definitionKey="customerDO" operationKey="findById" type="lookup"/>
                   <flowable:inputParameter name="age" value="${'$'}{customerAge}"/>
                 </extensionElements>
               </serviceTask>"""
        )
        assertEquals(
            listOf("in|inputParameter|\${city}->city [expression=true]", "out|outputParameter|text->answer"),
            params(p).filter { it["element"] == "ai" }.map { sig(it) },
        )
        @Suppress("UNCHECKED_CAST")
        val tasks = (p["serviceTasks"] as List<Map<String, Any?>>).associateBy { it["id"] }
        assertEquals("itAgent", tasks["ai"]!!["agentModelKey"])
        assertEquals("default", tasks["ai"]!!["agentOperationKey"])
        assertEquals("customerDO", tasks["lookup"]!!["dataObjectKey"])
        assertEquals("findById", tasks["lookup"]!!["dataObjectOperationKey"])
        assertTrue(ctx.refs.any { it["rel"] == "agentMapping" && it["value"] == "itAgent" })
    }

    @Test
    fun eventVariableMappingAndResultVariableFlavours() {
        val (p, _) = bpmn(
            """<startEvent id="evStart">
                 <extensionElements>
                   <flowable:eventType><![CDATA[testEvent]]></flowable:eventType>
                   <flowable:eventOutParameter source="id" target="eventId"/>
                 </extensionElements>
               </startEvent>
               <serviceTask id="sendEv" flowable:type="send-event">
                 <extensionElements>
                   <flowable:eventInParameter source="${'$'}{age}" target="age"/>
                 </extensionElements>
               </serviceTask>
               <serviceTask id="initVars" flowable:type="init-variables">
                 <extensionElements>
                   <flowable:variableMapping name="stage" valueType="string" value="new"/>
                   <flowable:variableMapping name="addr" valueType="jsonObject" valueExpression="${'$'}{json:object()}"/>
                 </extensionElements>
               </serviceTask>
               <serviceTask id="http" flowable:type="http" flowable:resultVariableName="httpResult"/>
               <scriptTask id="calc" scriptFormat="groovy" flowable:resultVariable="scriptOut">
                 <script>x = 1</script>
               </scriptTask>"""
        )
        assertEquals(
            listOf(
                "out|eventOutParameter|id->eventId",
                "in|eventInParameter|\${age}->age [expression=true]",
                "in|variableMapping|new->stage [type=string]",
                "in|variableMapping|\${json:object()}->addr [type=jsonObject,expression=true]",
                "out|resultVariable|null->httpResult",
                "out|resultVariable|null->scriptOut",
            ),
            params(p).map { sig(it) },
        )
    }

    @Test
    fun httpTaskFieldInjectionsArePersistedNotTreatedAsParameters() {
        val (p, _) = bpmn(
            """<serviceTask id="http" name="Call REST" flowable:type="http">
                 <extensionElements>
                   <flowable:field name="requestUrl"><flowable:string>/api/x</flowable:string></flowable:field>
                   <flowable:field name="requestMethod"><flowable:string>GET</flowable:string></flowable:field>
                 </extensionElements>
               </serviceTask>"""
        )
        assertTrue("field injections are configuration, not variable mappings", params(p).isEmpty())
        @Suppress("UNCHECKED_CAST")
        val task = (p["serviceTasks"] as List<Map<String, Any?>>)[0]
        assertEquals(mapOf("requestUrl" to "/api/x", "requestMethod" to "GET"), task["fields"])
    }

    @Test
    fun cmmnProcessAndCaseTaskMappingsAreRolledUpOnTheCase() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
                         xmlns:flowable="http://flowable.org/cmmn">
              <case id="c" name="C">
                <casePlanModel id="plan">
                  <planItem id="pi1" definitionRef="startOrder"/>
                  <planItem id="pi2" definitionRef="lookup"/>
                  <processTask id="startOrder" name="Start order" processRef="orderProcess">
                    <extensionElements>
                      <flowable:in source="caseVar" target="procVar"/>
                      <flowable:out source="procResult" target="caseResult"/>
                    </extensionElements>
                  </processTask>
                  <task id="lookup" name="Lookup" flowable:type="service-registry">
                    <extensionElements>
                      <flowable:serviceMapping serviceModelKey="custSvc" operationKey="findAll"/>
                      <flowable:outputParameter name="id" value="custId"/>
                    </extensionElements>
                  </task>
                </casePlanModel>
              </case>
            </definitions>"""
        val ctx = Ctx()
        val case = BackendModelParsers.parseCmmn(xml.toByteArray(), ctx, "c.cmmn")[0]
        assertEquals(
            listOf(
                "in|in|caseVar->procVar",
                "out|out|procResult->caseResult",
                "out|outputParameter|id->custId",
            ),
            params(case).map { sig(it) },
        )
        assertEquals(listOf("startOrder", "startOrder", "lookup"), params(case).map { it["element"] })
        assertEquals("service-registry", params(case).last()["elementSubType"])
    }

    @Test
    fun formKeyPushedInThroughAnInMappingIsStillDetected() {
        val (_, ctx) = bpmn(
            """<callActivity id="callSub" calledElement="sub">
                 <extensionElements><flowable:in source="myForm" target="formKey"/></extensionElements>
               </callActivity>"""
        )
        assertTrue(ctx.refs.any { it["rel"] == "task-form-mapping" && it["value"] == "myForm" })
    }

    @Test
    fun onlyCallerSideVariablesBecomeParameterFlows() {
        val (_, ctx) = bpmn(
            """<callActivity id="callSub" calledElement="sub">
                 <extensionElements><flowable:in source="orderId" target="subOrderId"/></extensionElements>
               </callActivity>
               <serviceTask id="svc" flowable:type="service-registry">
                 <extensionElements>
                   <flowable:inputParameter name="customerName" value="${'$'}{custVar}"/>
                   <flowable:outputParameter name="id" value="newCustomerId"/>
                 </extensionElements>
               </serviceTask>"""
        )
        // `<flowable:in>` binds a variable on both sides; a service parameter name (`customerName`) and
        // an expression value (`${custVar}`) do not — treating them as variables invents phantom ones.
        assertEquals(
            setOf("orderId", "subOrderId", "newCustomerId"),
            ctx.paramFlows.map { it["variable"] }.toSet(),
        )
    }

    /** `"variable|dir|via"`, plus `@scope` when the site belongs to another model's variable space. */
    private fun siteSig(s: Map<String, Any?>): String =
        "${s["variable"]}|${s["dir"]}|${s["via"]}" + (s["scope"]?.let { "@$it" } ?: "")

    @Test
    fun eachMappingSideProvesAReadOrAWriteInTheRightScope() {
        val (_, ctx) = bpmn(
            """<callActivity id="callSub" calledElement="fulfilmentProcess">
                 <extensionElements>
                   <flowable:in source="orderId" target="subOrderId"/>
                   <flowable:out source="subTotal" target="total"/>
                 </extensionElements>
               </callActivity>
               <serviceTask id="svc" flowable:type="service-registry">
                 <extensionElements>
                   <flowable:serviceMapping serviceModelKey="custSvc" operationKey="findById"/>
                   <flowable:inputParameter name="customerName" value="${'$'}{custVar}"/>
                   <flowable:outputParameter name="id" value="newCustomerId"/>
                 </extensionElements>
               </serviceTask>"""
        )
        assertEquals(
            listOf(
                // the caller reads `orderId` to hand it over…
                "orderId|read|inParameterSource",
                // …and the value lands in a variable of the *callee*, which is where a reader must be
                // looked for. Attributing it to this process would check the wrong model.
                "subOrderId|write|inParameter@fulfilmentProcess",
                "subTotal|read|outParameterSource@fulfilmentProcess",
                "total|write|outParameter",
                // an `inputParameter` name is the service's contract, not a variable — nothing recorded
                "newCustomerId|write|outputParameter",
            ),
            ctx.varSites.map { siteSig(it) },
        )
    }

    @Test
    fun anInMappingWithNoCalleeAtAllCannotBeJudged() {
        // Without a `calledElement` there is no scope to search for readers — not even a name to report as
        // "not in this project". Recording the write and admitting the readers are unknown is all Atlas can
        // honestly do; treating it as a local write would report a variable the callee may well consume.
        val (_, ctx) = bpmn(
            """<callActivity id="callSub">
                 <extensionElements><flowable:in source="orderId" target="subOrderId"/></extensionElements>
               </callActivity>"""
        )
        assertEquals(listOf("orderId|read|inParameterSource", "subOrderId|write|inParameter"),
            ctx.varSites.map { siteSig(it) })
        assertTrue("subOrderId" in ctx.varReadsUnknown)
    }

    @Test
    fun anEventPayloadSourceIsAReadRatherThanNothingAtAll() {
        // `eventInParameter`'s target is the event's own field name, so the kind is in
        // TARGET_IS_CONTRACT — which used to drop the mapping entirely and leave the variable it sends
        // looking like nothing reads it.
        val (_, ctx) = bpmn(
            """<serviceTask id="sendEv" flowable:type="send-event">
                 <extensionElements>
                   <flowable:eventType><![CDATA[orderPlaced]]></flowable:eventType>
                   <flowable:eventInParameter source="orderId" target="id"/>
                 </extensionElements>
               </serviceTask>"""
        )
        assertEquals(listOf("orderId|read|eventInParameter"), ctx.varSites.map { siteSig(it) })
        assertEquals(listOf("orderId"), ctx.paramFlows.map { it["variable"] })
    }

    @Test
    fun theEngineWrittenAttributesAreWritesAndACollectionIsARead() {
        val ctx = Ctx()
        VarHarvest.collectDirectedVars(
            ctx,
            """<userTask id="t" flowable:assignee="x">
                 <multiInstanceLoopCharacteristics flowable:collection="orderLines"
                                                   flowable:elementVariable="line"/>
               </userTask>
               <serviceTask flowable:resultVariableName="callResult"/>
               <process flowable:initiatorVariableName="starter"/>
               <extensionElements><flowable:variableName>watched</flowable:variableName></extensionElements>
               <flowable:variableEventListener variableName="watchedAttr"/>""",
            listOf("process:p"),
        )
        assertEquals(
            listOf(
                "line|write|multiInstanceElement",
                // read, not written — the loop iterates over it. Recovering this direction is what lets
                // the undirected `varUse` bucket be ignored without losing the read.
                "orderLines|read|multiInstanceCollection",
                "starter|write|initiator",
            ),
            ctx.varSites.map { siteSig(it) }.sorted(),
        )
        // `resultVariableName` is deliberately absent: `Ctx.addParams` already records it structurally,
        // with the element it belongs to. Harvesting it here too would report one write twice.
        assertTrue(ctx.varSites.none { it["variable"] == "callResult" })
        // a variable listener watches a name rather than reading or writing it — direction not ours to
        // declare, so the unused check must stay quiet about it
        assertEquals(setOf("watchedAttr"), ctx.varReadsUnknown)
    }

    @Test
    fun actionBotInputsComeFromSignalVariablesConfigAndTheFlwScriptApi() {
        val json = """{
          "key": "myAction", "name": "My Action", "botKey": "script-evaluation-bot",
          "signalVariableNames": ["approved"],
          "config": {
            "processDefinitionKey": "orderProcess",
            "scriptInfo": { "language": "javascript",
              "script": "const a = flw.getInput('amount'); flw.setOutput(\"result\", flw.getInput('amount'));" }
          }
        }"""
        val ctx = Ctx()
        val action = ModelParsers.parseAction(json.toByteArray(), ctx, "a.action")
        assertEquals(
            listOf(
                "in|signalVariable|null->approved",
                "in|config|orderProcess->processDefinitionKey",
                // the script reads `amount` twice — a payload key is listed once, not per occurrence
                "in|flwScript|null->amount",
                "out|flwScript|null->result",
            ),
            params(action).map { sig(it) },
        )
        assertEquals(setOf("script-evaluation-bot"), params(action).map { it["element"] }.toSet())
        // a config key is bot wiring, not a variable — only the real inputs/outputs become flows
        assertEquals(setOf("approved", "amount", "result"), ctx.paramFlows.map { it["variable"] }.toSet())
        assertEquals(
            listOf(
                // The bot script and the payload mapping it produces are two views of the same call, so
                // each name is recorded twice — once as the `flw.*` API the script performs, once as the
                // payload contract it declares. Both are true, and the directions agree.
                "amount|read|flwPayload", "amount|read|scriptApi",
                // to pass a variable into the signalled instance the action first reads it
                "approved|read|signalVariable",
                "result|write|flwPayload", "result|write|scriptApi",
            ),
            ctx.varSites.map { siteSig(it) }.sorted(),
        )
        // `flw.setOutput` writes a value the *caller* consumes — a form button's `{{$response…}}`, the
        // Work UI, a REST client. Atlas cannot follow any of those, so it must never call it unread.
        assertEquals(setOf("result"), ctx.varReadsUnknown)
    }

    private fun form(nodeJson: String): Pair<Map<String, Any?>, Ctx> {
        val json = """{"metadata":{"key":"f","name":"F","modelType":"form"},"rows":[[$nodeJson]]}"""
        val ctx = Ctx()
        return ModelParsers.parseForm(json.toByteArray(), ctx, "f.form") to ctx
    }

    @Test
    fun actionButtonPayloadIsReadFromBothMappings() {
        val (f, ctx) = form(
            """{"id":"btn","type":"workAction","label":"Check email","extraSettings":{
                 "actionDefinitionKey":"checkEmailAction",
                 "sendPayloadMapping":[{"name":"email","expression":"{{contactEmail}}"}],
                 "responsePayloadMapping":[{"name":"${'$'}temp.unique",
                                           "expression":"{{${'$'}response.executionPayload.isUnique}}"}]}}"""
        )
        assertEquals(
            listOf(
                "in|sendPayloadMapping|{{contactEmail}}->email [expression=true]",
                "out|responsePayloadMapping|{{\$response.executionPayload.isUnique}}->\$temp.unique [expression=true]",
            ),
            params(f).map { sig(it) },
        )
        // the callee travels with each record — that is what lets the action show its callers' payloads
        assertTrue(params(f).all { it["refKind"] == "action" && it["refKey"] == "checkEmailAction" })
        assertEquals(setOf("btn"), params(f).map { it["element"] }.toSet())
        assertEquals(setOf("workAction"), params(f).map { it["elementType"] }.toSet())
        assertTrue(ctx.refs.any { it["rel"] == "triggers-action" && it["value"] == "checkEmailAction" })
        // a `{{…}}` binding is no more a caller variable than a callee parameter name is
        assertTrue(ctx.paramFlows.isEmpty())
    }

    @Test
    fun restButtonWholeBodyExpressionAndHeadersAndErrorMapping() {
        // The real Design shape (palette `base-rest-button`): the URL lives on `extraSettings.url`, the
        // caption on `extraSettings.text`, and there is no `label` at all.
        val (f, _) = form(
            """{"id":"rb","type":"restButton","extraSettings":{
                 "text":"Save","method":"post","url":"/api/x",
                 "sendPayloadMapping":"{{Object.assign(${'$'}item, ${'$'}edit)}}",
                 "headerPropertyMapping":[{"name":"X-Tenant","value":"acme"}],
                 "errorResponsePayloadMapping":[{"name":"${'$'}temp.err","expression":"{{${'$'}response.message}}"}]}}"""
        )
        assertEquals(
            listOf(
                // a bare string is the whole request body — there is no per-key mapping to show
                "in|sendPayloadMapping|{{Object.assign(\$item, \$edit)}}->null [expression=true]",
                "in|header|acme->X-Tenant",
                "error-out|errorResponsePayloadMapping|{{\$response.message}}->\$temp.err [expression=true]",
            ),
            params(f).map { sig(it) },
        )
        // no model reference on a plain REST button — the URL is the callee, and the label falls back to
        // the button text because the node carries no `label`
        assertEquals(setOf("rest"), params(f).map { it["refKind"] }.toSet())
        assertEquals(setOf("/api/x"), params(f).map { it["refKey"] }.toSet())
        assertEquals(setOf("Save"), params(f).map { it["elementName"] }.toSet())
    }

    @Test
    fun restButtonEndpointIsOnTheModelWithItsButtonAndVerb() {
        val (f, ctx) = form(
            """{"id":"rest-button2","type":"restButton","extraSettings":{
                 "text":"Select all","method":"post","path":"data",
                 "url":"{{endpoints.baseUrl}}/api/myService/my-api"}}"""
        )
        // The endpoint has to be readable off the model itself: it used to be recorded only into the
        // shared Ctx, which the explorer payload never carries — so it was neither shown nor findable.
        assertEquals(
            listOf(mapOf(
                "where" to "rest-button2", "method" to "POST",
                "url" to "{{endpoints.baseUrl}}/api/myService/my-api", "path" to "data",
            )),
            f["restCalls"],
        )
        // …and the Ctx entry now names the button and the real verb (it said `(button)` and null before)
        val call = ctx.restCalls.single()
        assertEquals("rest-button2", call["where"])
        assertEquals("POST", call["method"])
        assertEquals("form-button", call["kind"])
        // a button has no `label`, so it only reaches `fields` via its `extraSettings.text`
        @Suppress("UNCHECKED_CAST") val fields = f["fields"] as List<Map<String, Any?>>
        assertEquals(listOf("rest-button2"), fields.map { it["id"] })
        assertEquals(listOf("Select all"), fields.map { it["label"] })
    }

    @Test
    fun restButtonWithoutAnExplicitMethodIsTheGetDefault() {
        // `extraSettings.method` is omitted whenever it is the palette default, which is most buttons.
        val (f, _) = form("""{"id":"rb","type":"restButton","extraSettings":{"text":"Load","url":"/api/x"}}""")
        @Suppress("UNCHECKED_CAST") val calls = f["restCalls"] as List<Map<String, Any?>>
        assertEquals("GET", calls.single()["method"])
    }

    @Test
    fun aSelectsLookupUrlIsADataSourceToo() {
        // `queryUrl` was read but its sibling `lookupUrl` — the id→label resolver — was not.
        val (f, ctx) = form(
            """{"id":"cust","type":"select","label":"Customer",
                 "extraSettings":{"queryUrl":"/api/customers","lookupUrl":"/api/customers/{{id}}"}}"""
        )
        assertEquals(
            listOf("/api/customers", "/api/customers/{{id}}"),
            @Suppress("UNCHECKED_CAST") (f["dataSources"] as List<Map<String, Any?>>).map { it["url"] },
        )
        assertEquals(setOf("form-query"), ctx.restCalls.map { it["kind"] }.toSet())
    }

    @Test
    fun agentButtonRecordsItsAgentAndItsPayload() {
        val (f, ctx) = form(
            """{"id":"ab","type":"workAgentButton","label":"Ask","extraSettings":{
                 "agentModel":{"agentModelKey":"supportAgent"},
                 "sendPayloadMapping":[{"name":"question","expression":"{{q}}"}]}}"""
        )
        assertEquals(listOf("in|sendPayloadMapping|{{q}}->question [expression=true]"), params(f).map { sig(it) })
        assertEquals("agent", params(f)[0]["refKind"])
        assertEquals("supportAgent", params(f)[0]["refKey"])
        // an agent button's model reference went unrecorded before — a service button's did not
        assertTrue(ctx.refs.any { it["rel"] == "field-agent" && it["value"] == "supportAgent" })
    }

    @Test
    fun aButtonWithoutAnyPayloadMappingAddsNothing() {
        val (f, _) = form("""{"id":"plain","type":"button","label":"Go","extraSettings":{"text":"Go"}}""")
        assertTrue(params(f).isEmpty())
    }

    // ---------- what a button does, on the button's own record ----------
    // The form's reference list always said an action was triggered; these pin the half that was
    // missing — by which button, with which settings, and under which condition.

    @Suppress("UNCHECKED_CAST")
    private fun fields(model: Map<String, Any?>): List<Map<String, Any?>> =
        model["fields"] as List<Map<String, Any?>>

    @Test
    fun anActionButtonCarriesTheActionItInvokes() {
        val (f, _) = form(
            """{"id":"btn","type":"workAction","visible":"{{amount > 0}}","enabled":true,"primary":true,
                "extraSettings":{"text":"Notify","actionDefinitionKey":"notifyAction",
                                 "navigationUrl":"/work/case/1","autoExecute":false}}"""
        )
        val b = fields(f).single()
        assertEquals(mapOf("kind" to "action", "key" to "notifyAction"), b["callee"])
        // `false` is the palette default on every flag and a literal `enabled:true` is every button
        // there is; only a condition is news.
        assertEquals(
            mapOf("navigationUrl" to "/work/case/1", "primary" to true, "visible" to "{{amount > 0}}"),
            b["settings"],
        )
        assertEquals("Notify", b["label"])
    }

    @Test
    fun anExpressionButtonCarriesItsExpressionAndWritesItsResult() {
        val (f, ctx) = form(
            """{"id":"total","type":"scriptButton","value":"{{orderTotal}}","extraSettings":{
                 "text":"Recalculate","script":"{{amount * 1.081}}","autoExecute":true,"timer":30000}}"""
        )
        val b = fields(f).single()
        assertEquals(
            mapOf("script" to "{{amount * 1.081}}", "timer" to 30000.0, "autoExecute" to true),
            b["settings"],
        )
        // The result lands in the button's own binding, and nothing else in the model writes it — so
        // without this site the target looked neither read nor written.
        assertEquals(listOf("orderTotal|write|scriptButton"), ctx.varSites.map { siteSig(it) })
    }

    @Test
    fun aCaptionlessButtonIsStillListedWithItsEndpoint() {
        // Half the REST buttons of one real project have neither `label` nor `extraSettings.text`: they
        // are icon-only, or captioned by `value`. They used to be dropped from the model entirely.
        val (f, _) = form(
            """{"id":"reload","type":"restButton","extraSettings":{"url":"/api/reload","method":"post"}}"""
        )
        val b = fields(f).single()
        assertEquals("reload", b["id"])
        assertEquals(mapOf("kind" to "rest", "key" to "/api/reload"), b["callee"])
        // the endpoint itself stays a REST call keyed by the same element id — not copied onto the row
        @Suppress("UNCHECKED_CAST")
        val calls = f["restCalls"] as List<Map<String, Any?>>
        assertEquals(listOf("reload" to "POST"), calls.map { it["where"] to it["method"] })
    }

    @Test
    fun aLinkButtonIsCaptionedByItsValue() {
        val (f, _) = form(
            """{"id":"portal","type":"linkButton","value":"Open portal",
                "extraSettings":{"url":"https://example.com","target":"_blank"}}"""
        )
        assertEquals("Open portal", fields(f).single()["label"])
        assertEquals(mapOf("target" to "_blank"), fields(f).single()["settings"])
    }

    @Test
    fun aFullPayloadFlagIsRecordedBecauseItOverridesTheMap() {
        // The Work runtime sends the whole payload and ignores `sendPayloadMapping` — a reader shown the
        // map alone would take it for the contract.
        val (f, ctx) = form(
            """{"id":"esc","type":"workAction","extraSettings":{"text":"Escalate",
                 "actionDefinitionKey":{"key":"escalateAction","id":"ACTION_MODEL-7"},
                 "sendFullPayload":true,"mapFullResponse":true,"mapResponseInsideScope":true,
                 "sendPayloadMapping":[{"name":"total","expression":"{{orderTotal}}"}]}}"""
        )
        val b = fields(f).single()
        assertEquals(mapOf("send" to "full-payload", "receive" to "full-response-in-scope"), b["payloadMode"])
        // the newer Design editor writes the reference as {key, id} — the key is what the graph joins on
        assertEquals(mapOf("kind" to "action", "key" to "escalateAction"), b["callee"])
        assertTrue(ctx.refs.any { it["rel"] == "triggers-action" && it["value"] == "escalateAction" })
        // the overridden mapping is still parsed: it is in the model, and the explorer marks it unused
        assertEquals(listOf("in|sendPayloadMapping|{{orderTotal}}->total [expression=true]"), params(f).map { sig(it) })
    }

    @Test
    fun aPlainFieldHasNothingToExpand() {
        val (f, _) = form("""{"id":"amount","type":"number","label":"Amount","isRequired":true}""")
        val b = fields(f).single()
        assertTrue(b["callee"] == null && b["settings"] == null && b["payloadMode"] == null)
    }

    @Test
    fun aHiddenAutoExecutingWorkerSaysSoAndStoresItsResponse() {
        // The commonest button in a real project is not a button: `visible:false` + `autoExecute` is a
        // worker that fires on its own. 252 of 338 buttons in one measured project are hidden this way.
        val (f, ctx) = form(
            """{"id":"score","type":"restButton","visible":false,"ignore":"{{!customer}}",
                "value":"{{creditScore}}","description":"Hidden worker",
                "i18n":{"en_us":{"extraSettings":{"text":"Refresh score"}}},
                "extraSettings":{"url":"/api/score","autoExecute":"{{customer}}","executeAlways":true,
                                 "valueExpression":"{{${'$'}response.score}}"}}"""
        )
        val b = fields(f).single()
        // the caption exists only as a localised override — before, such a button had no name at all
        assertEquals("Refresh score", b["label"])
        assertEquals("Hidden worker", b["description"])
        assertEquals("{{creditScore}}", b["stores"])
        assertEquals(
            mapOf("visible" to false, "ignore" to "{{!customer}}",
                "valueExpression" to "{{\$response.score}}", "autoExecute" to "{{customer}}",
                "executeAlways" to true),
            b["settings"],
        )
        // the response lands in a variable, and this button is the only thing that writes it
        assertEquals(listOf("creditScore|write|restButton"), ctx.varSites.map { siteSig(it) })
    }

    @Test
    fun gatesAreRecordedOnlyWhenTheyDepartFromTheDefault() {
        val (f, _) = form(
            """{"id":"note","type":"textarea","label":"Note","visible":true,"enabled":"{{isBoss}}",
                "ignore":false}"""
        )
        // `visible:true` and `ignore:false` are every component there is; only the condition is news
        assertEquals(mapOf("enabled" to "{{isBoss}}"), fields(f).single()["settings"])
    }

    @Test
    fun anActionButtonsPlaceholderValueIsNotAStoreTarget() {
        // Design writes `value: "."` on an action button. Reading that as "the result is stored in ." is
        // exactly the kind of thing a reader would then go looking for.
        val (f, ctx) = form(
            """{"id":"act","type":"workAction","value":".","extraSettings":{
                 "text":"Go","actionDefinitionKey":"someAction"}}"""
        )
        assertEquals(null, fields(f).single()["stores"])
        assertTrue(ctx.varSites.isEmpty())
    }

    @Test
    fun serviceOperationCarriesBothHalvesOfItsContract() {
        val json = """{
          "key": "custSvc", "name": "Customer", "type": "rest",
          "outputParameters": [{"name": "fallbackOut", "type": "string"}],
          "operations": [
            {"key": "findById", "config": {"method": "GET", "url": "/c/{id}"},
             "inputParameters": [{"name": "id", "type": "string"}],
             "outputParameters": [{"name": "customerName", "type": "string"}]},
            {"key": "findAll", "config": {"method": "GET", "url": "/c"},
             "inputParameters": []}
          ]
        }"""
        val svc = ModelParsers.parseService(json.toByteArray(), Ctx(), "s.service")
        @Suppress("UNCHECKED_CAST")
        val ops = (svc["operations"] as List<Map<String, Any?>>).associateBy { it["key"] }
        assertEquals(listOf(mapOf("name" to "id", "type" to "string")), ops["findById"]!!["params"])
        assertEquals(listOf(mapOf("name" to "customerName", "type" to "string")), ops["findById"]!!["outParams"])
        // an operation that declares none of its own inherits the service-level list
        assertEquals(listOf(mapOf("name" to "fallbackOut", "type" to "string")), ops["findAll"]!!["outParams"])
    }
}
