package com.flowable.atlas.parsing

import com.flowable.atlas.graph.Ctx
import com.flowable.atlas.parsing.AtlasXml.El
import com.flowable.atlas.script.ScriptContext
import com.flowable.atlas.script.ScriptValidator

/**
 * The BPMN and CMMN parsers — a port of `parse_bpmn` and `parse_cmmn` (+ its helpers
 * `_cmmn_service_refs`, `_cmmn_def`, `_cmmn_walk`) in `flowable_atlas.py` (~lines 359-716).
 *
 * Each parser takes the raw model bytes, the shared [Ctx] (into which cross-model references, access
 * entries, REST calls and variable usage are recorded), and the model file's project-relative path; it
 * returns one ordered map per definition found in the file (`<process>` / `<case>`). Keys and value
 * shapes mirror the Python dicts exactly, including conditionally-added keys.
 */
object BackendModelParsers {

    /** `[#$]{ beanName …` — the leading bean/context identifier of an EL delegate expression. */
    private val BEAN_RE = Regex("[#$]\\{\\s*([A-Za-z_]\\w*)")

    // -----------------------------------------------------------------------
    // BPMN
    // -----------------------------------------------------------------------

    /** `.bpmn` — one entry per `<process>`, with its tasks/events/gateways and cross-model refs. */
    @Suppress("UNCHECKED_CAST")
    fun parseBpmn(data: ByteArray, ctx: Ctx, ffile: String): List<Map<String, Any?>> {
        val root = AtlasXml.parse(data)
        val processes = ArrayList<Map<String, Any?>>()
        // Root-level <message>/<signal>/<error>/<escalation> definitions: events reference them by
        // id, but cross-model correlation happens by NAME (messages/signals) or CODE (errors/
        // escalations) — exactly what the engine's event subscriptions store.
        fun defNames(tag: String, nameAttr: String): Map<String, String> =
            root.iter(tag).mapNotNull { d ->
                d.attr("id")?.let { id -> id to (d.attr(nameAttr)?.ifEmpty { null } ?: id) }
            }.toMap()
        val msgDefs = defNames("message", "name")
        val sigDefs = defNames("signal", "name")
        val errDefs = defNames("error", "errorCode")
        val escDefs = defNames("escalation", "escalationCode")
        fun correlate(kind: String, refOrName: String): String = when (kind) {
            "message" -> msgDefs[refOrName] ?: refOrName
            "signal" -> sigDefs[refOrName] ?: refOrName
            "error" -> errDefs[refOrName] ?: refOrName
            "escalation" -> escDefs[refOrName] ?: refOrName
            else -> refOrName
        }
        for (proc in root.iter("process")) {
            val pkey = proc.attr("id")
            val userTasks = ArrayList<Any?>()
            val serviceTasks = ArrayList<Any?>()
            val scriptTasks = ArrayList<Any?>()
            val ruleTasks = ArrayList<Any?>()
            val callActivities = ArrayList<Any?>()
            val subProcesses = ArrayList<Any?>()
            val events = ArrayList<Any?>()
            val gateways = ArrayList<Any?>()
            val conditions = ArrayList<Any?>()
            // Every sequence flow, conditional or not. `conditions` only ever kept the conditional ones,
            // which meant nothing downstream could answer "what runs after this task" — the most basic
            // question about a process. See the `flows` entry in [info].
            val flows = ArrayList<Any?>()
            val otherTasks = ArrayList<Any?>()
            val multiInstance = ArrayList<Any?>()
            val ioParameters = ArrayList<Map<String, Any?>>()
            // `<dataObject>` declarations — the process's own variables, with the type and default the
            // modeller gave them.
            val dataObjects = ArrayList<Any?>()
            val info = linkedMapOf<String, Any?>(
                "key" to pkey, "name" to proc.attr("name"), "file" to ffile,
                "documentation" to proc.textOfDescendant("documentation"),
                "candidateStarterGroups" to proc.attr("candidateStarterGroups"),
                "userTasks" to userTasks, "serviceTasks" to serviceTasks, "scriptTasks" to scriptTasks,
                "ruleTasks" to ruleTasks, "callActivities" to callActivities, "subProcesses" to subProcesses,
                "events" to events, "gateways" to gateways, "conditions" to conditions,
                // The process's topology: `{id, from, to, name?, condition?}` per sequence flow, in
                // document order. `conditions` stays as the conditional subset the explorer matches to
                // its diagram edges; `flows` is what lets a reader (or the Markdown report) walk the
                // process from its start event instead of reading elements grouped by type.
                "flows" to flows,
                "otherTasks" to otherTasks, "listeners" to ArrayList<Any?>(), "multiInstance" to multiInstance,
                "ioParameters" to ioParameters, "dataObjects" to dataObjects,
            )

            val listeners = XmlHelpers.readListeners(proc)
            info["listeners"] = listeners
            XmlHelpers.collectListenerRefs(ctx, pkey, "bpmn", ffile, listeners)
            ctx.addAccess(pkey, "process", "start", "start",
                proc.attr("candidateStarterGroups"), proc.attr("candidateStarterUsers"))

            // process-level extension references (parity with CMMN cases)
            fun ensureModelRefs(): ArrayList<Any?> {
                (info["modelRefs"] as? ArrayList<Any?>)?.let { return it }
                val l = ArrayList<Any?>()
                info["modelRefs"] = l
                return l
            }
            val pext = XmlHelpers.extEl(proc)
            if (pext != null) {
                val modelRefs = ensureModelRefs()
                for ((tag, kind) in listOf(
                    "sla-definition-key" to "sla", "security-policy-model" to "securityPolicy",
                    "eventType" to "event", "channelKey" to "channel",
                )) {
                    val v = pext.childText(tag)
                    if (truthy(v)) {
                        modelRefs.add(linkedMapOf("rel" to tag, "key" to v))
                        ctx.addRef(pkey, "bpmn", ffile, tag, kind, v)
                    }
                }
                val dd = pext.findChild("data-dictionary-model")
                if (dd != null && truthy(dd.attr("key"))) {
                    modelRefs.add(linkedMapOf("rel" to "data-dictionary", "key" to dd.attr("key")))
                    ctx.addRef(pkey, "bpmn", ffile, "data-dictionary", "dataDictionary", dd.attr("key"))
                }
            }
            for (sq in proc.iter("processSequence") + proc.iter("caseSequence")) {
                val t = sq.text
                if (!t.isNullOrEmpty()) {
                    ensureModelRefs().add(linkedMapOf("rel" to "sequence", "key" to t.trim()))
                    ctx.addRef(pkey, "bpmn", ffile, "uses-sequence", "sequence", t.trim())
                }
            }

            for (el in iterAll(proc)) {
                val tag = el.tag
                val eid = el.attr("id")
                val ename = el.attr("name")
                // forms linked via Design extension elements (work-/start-form) anywhere in the tree
                for ((rel, fk) in XmlHelpers.designFormKeys(el)) {
                    ctx.addRef(pkey, "bpmn", ffile, rel, "form", fk)
                }
                // static model keys (case-view/page config) + watcher/participant group permissions
                XmlHelpers.collectDesignExtensionRefs(ctx, pkey, "bpmn", "process", ffile, el)
                // Event-registry links under an element's extensionElements (process-level handled above)
                if (el !== proc) {
                    val eext = XmlHelpers.extEl(el)
                    if (eext != null) {
                        val ev = eext.childText("eventType")
                        if (!ev.isNullOrEmpty()) {
                            // Direction follows the element, not just a `type` attribute: throw/end
                            // events and send-event tasks publish; start/catch/boundary consume.
                            val rel = when {
                                el.attr("type") in listOf("send-event", "sendEvent") -> "sends-event"
                                tag in listOf("intermediateThrowEvent", "endEvent") -> "sends-event"
                                else -> "receives-event"
                            }
                            ctx.addRef(pkey, "bpmn", ffile, rel, "event", ev)
                        }
                        ctx.addRef(pkey, "bpmn", ffile, "trigger-event", "event", eext.childText("triggerEventType"))
                        // send/receive via an explicit channel + template refs (email/document tasks)
                        ctx.addRef(pkey, "bpmn", ffile, "via-channel", "channel", eext.childText("channelKey"))
                        for (tk in listOf("templateKey", "subjectTemplateModelKey", "bodyTemplateModelKey")) {
                            ctx.addRef(pkey, "bpmn", ffile, tk, "template", eext.childText(tk))
                        }
                        for (dec in eext.findChildren("documentEventConfiguration")) {
                            ctx.addRef(pkey, "bpmn", ffile, "document-event", "document",
                                dec.attr("definitionKey") ?: dec.childText("definitionKey"))
                        }
                    }
                }
                // Listeners on the element itself. Until now only the process and its user tasks were
                // read, so an execution listener on a service task, gateway or start event produced no
                // reference at all — its class/bean/script was invisible to the graph and to search.
                val elListeners = if (el === proc) emptyList() else XmlHelpers.readListeners(el)
                if (elListeners.isNotEmpty()) {
                    XmlHelpers.collectListenerRefs(ctx, pkey, "bpmn", ffile, elListeners, eid, ename)
                }
                val mi = el.findChild("multiInstanceLoopCharacteristics")
                if (mi != null && tag != "process") {
                    multiInstance.add(linkedMapOf(
                        "activity" to eid, "collection" to mi.attr("collection"),
                        "elementVariable" to mi.attr("elementVariable"),
                        "cardinality" to mi.childText("loopCardinality"),
                        "sequential" to mi.attr("isSequential"),
                    ))
                }

                // In/out parameter mappings, read once for every element: Flowable allows them on call
                // activities, case/service-registry/agent/data-object/HTTP/init-variables service tasks,
                // script tasks, receive tasks and event-registry start/boundary events, and they all use
                // the same handful of extension elements (see XmlHelpers.readIoParams).
                val elParams = XmlHelpers.readIoParams(el)
                if (elParams.isNotEmpty()) {
                    ctx.addParams(ioParameters, pkey, eid, ename, tag, elParams, el.attr("type"),
                        XmlHelpers.calleeOf(el))
                    // a form key can also arrive as the target of an in-mapping (parity with CMMN, which
                    // checks this on every plan-item definition)
                    for (fk in XmlHelpers.inoutFormKeys(elParams)) {
                        ctx.addRef(pkey, "bpmn", ffile, "task-form-mapping", "form", fk)
                    }
                }

                when {
                    // The only place BPMN states outright that a process *has* a variable. Until now the
                    // graph could not see it: a declared variable existed only if something happened to
                    // mention it elsewhere. The engine creates it, so it is a write and nothing more —
                    // whether anyone reads it is exactly the question worth asking about a declaration.
                    tag == "dataObject" -> {
                        val vname = el.attr("name")?.ifEmpty { null } ?: eid
                        dataObjects.add(linkedMapOf(
                            "id" to eid, "name" to ename, "type" to el.attr("itemSubjectRef"),
                            "default" to el.textOfDescendant("value"),
                        ))
                        ctx.addVar(pkey, vname)
                        ctx.addVarSite(pkey, vname, Ctx.WRITE, "dataObject", eid, ename, "dataObject")
                    }
                    // A legacy BPMN form property is rendered as a field: read to prefill, written on
                    // submit — the same two-way binding a Design form field has.
                    tag == "formProperty" -> {
                        val fid = el.attr("id")?.ifEmpty { null }
                        ctx.addVar(pkey, fid)
                        for (d in listOf(Ctx.READ, Ctx.WRITE)) {
                            ctx.addVarSite(pkey, fid, d, "formProperty", fid, ename, "formProperty")
                        }
                    }
                    tag == "userTask" -> {
                        val ut = linkedMapOf<String, Any?>(
                            "id" to eid, "name" to ename, "assignee" to el.attr("assignee"),
                            "candidateGroups" to el.attr("candidateGroups"),
                            "formKey" to el.attr("formKey"),
                        )
                        // scheduling/classification attributes only when the model declares them, so
                        // the common case keeps the minimal five-key shape
                        for (a in listOf("dueDate", "priority", "category")) {
                            el.attr(a)?.ifEmpty { null }?.let { ut[a] = it }
                        }
                        userTasks.add(withElementExtras(ut, el, elListeners))
                        ctx.addRef(pkey, "bpmn", ffile, "userTask-form", "form", ut["formKey"])
                        ctx.addAccess(pkey, "process", "task:$eid", "assign",
                            el.attr("candidateGroups"), pyOr(el.attr("candidateUsers"), el.attr("assignee")))
                    }
                    tag == "serviceTask" -> {
                        val st = linkedMapOf<String, Any?>(
                            "id" to eid, "name" to ename, "class" to el.attr("class"),
                            "expression" to el.attr("expression"),
                            "delegateExpression" to el.attr("delegateExpression"),
                            "type" to el.attr("type"), "resultVariable" to el.attr("resultVariableName"),
                        )
                        serviceTasks.add(withElementExtras(st, el, elListeners))
                        ctx.addRef(pkey, "bpmn", ffile, "serviceTask-class", "class", st["class"])
                        // Both `delegateExpression` and `expression` reference a bean — a bean-only
                        // `flowable:expression="${myBean}"` has no method call, so the whole-file
                        // METHOD_CALL harvest never sees it either.
                        for (exAttr in listOf("delegateExpression", "expression")) {
                            val exv = st[exAttr] as? String
                            if (exv.isNullOrEmpty()) continue
                            for (m in BEAN_RE.findAll(exv)) {
                                val b = m.groupValues[1]
                                if (b !in Constants.FLOWABLE_CONTEXT)
                                    ctx.addRef(pkey, "bpmn", ffile, "serviceTask-delegate", "bean", b)
                            }
                        }
                        val type = el.attr("type")
                        if (type == "http") {
                            val f = XmlHelpers.readFields(el)
                            if (truthy(f["requestUrl"])) {
                                ctx.restCalls.add(linkedMapOf(
                                    "source" to pkey, "sourceFile" to ffile, "where" to eid,
                                    "method" to pyOr(f["requestMethod"], "GET"),
                                    "url" to f["requestUrl"], "kind" to "http-task",
                                ))
                            }
                        } else if (type == "send-event" || type == "sendEvent") {
                            ctx.addRef(pkey, "bpmn", ffile, "sends-event", "event", XmlHelpers.readFields(el)["eventType"])
                        } else if (type == "dmn") {
                            val f = XmlHelpers.readFields(el)
                            val dref = pyOr(f["decisionTableReferenceKey"], f["decisionServiceReferenceKey"])
                            ruleTasks.add(linkedMapOf("id" to eid, "name" to ename, "decisionRef" to dref))
                            ctx.addRef(pkey, "bpmn", ffile, "ruleTask-decision", "decision", dref)
                        } else if (type == "case") {
                            // case service task — starts a CMMN case by definition key (attribute,
                            // with a field-injection fallback for older exports)
                            val ck = pyOr(el.attr("caseDefinitionKey"), XmlHelpers.readFields(el)["caseDefinitionKey"])
                            st["caseDefinitionKey"] = ck
                            ctx.addRef(pkey, "bpmn", ffile, "caseTask", "case", ck)
                        } else if (type == "external-worker") {
                            // external worker task — the topic names the external system's queue
                            val topic = pyOr(el.attr("topic"), XmlHelpers.readFields(el)["topic"])
                            st["topic"] = topic
                            ctx.addRef(pkey, "bpmn", ffile, "external-topic", "topic", topic)
                        }
                        // Which model this task calls — service registry / data object / agent. Shared with
                        // CMMN: until now only the CMMN side read these, so a BPMN service-registry or
                        // agent task produced no reference at all and its parameters had no callee.
                        st.putAll(XmlHelpers.readTaskMappings(ctx, pkey, "bpmn", ffile, el))
                        // Field injections are this task's static configuration (an HTTP task's request
                        // URL/method, a delegate's constants). Previously read only to probe seven
                        // hard-coded names and then discarded.
                        val fields = XmlHelpers.readFields(el)
                        if (fields.isNotEmpty()) st["fields"] = fields
                        XmlHelpers.resultVariableParam("resultVariable", st["resultVariable"] as? String)
                            ?.let { ctx.addParams(ioParameters, pkey, eid, ename, tag, listOf(it), type,
                                XmlHelpers.calleeOf(el)) }
                    }
                    tag == "scriptTask" -> {
                        val body = el.childText("script")
                        val st = linkedMapOf<String, Any?>(
                            "id" to eid, "name" to ename, "format" to el.attr("scriptFormat"),
                            "script" to body, "resultVariable" to el.attr("resultVariable"),
                        )
                        ScriptValidator.problemDicts(body, el.attr("scriptFormat"), formatRequired = true,
                            context = ScriptContext.BPMN_SCRIPT_TASK)
                            .ifEmpty { null }?.let { st["problems"] = it }
                        scriptTasks.add(withElementExtras(st, el, elListeners))
                        VarHarvest.collectScriptVars(ctx, body, listOf(pkey), el.attr("scriptFormat"),
                            eid, ename, "scriptTask")
                        ctx.addVar(pkey, el.attr("resultVariable"))
                        XmlHelpers.resultVariableParam("resultVariable", el.attr("resultVariable"))
                            ?.let { ctx.addParams(ioParameters, pkey, eid, ename, tag, listOf(it)) }
                    }
                    tag == "businessRuleTask" -> {
                        val f = XmlHelpers.readFields(el)
                        val dref = pyOr(pyOr(el.attr("decisionTableReferenceKey"), f["decisionTableReferenceKey"]),
                            el.textOfDescendant("decisionRef"))
                        ruleTasks.add(withElementExtras(
                            linkedMapOf("id" to eid, "name" to ename, "decisionRef" to dref), el, elListeners))
                        ctx.addRef(pkey, "bpmn", ffile, "ruleTask-decision", "decision", dref)
                    }
                    tag == "callActivity" -> {
                        val called = el.attr("calledElement")
                        // calledElementType is "key" (default) or "id" — an id is a deployment-time
                        // definition id, not a model key, so resolving it by key is only a guess.
                        val calledType = el.attr("calledElementType")
                        callActivities.add(withElementExtras(linkedMapOf(
                            "id" to eid, "name" to ename, "calledElement" to called,
                            "calledElementType" to calledType,
                        ), el, elListeners))
                        ctx.addRef(pkey, "bpmn", ffile, "callActivity", "process", called,
                            suspect = calledType.equals("id", ignoreCase = true))
                    }
                    tag in listOf("subProcess", "transaction", "adhocSubProcess") -> {
                        subProcesses.add(withElementExtras(linkedMapOf(
                            "id" to eid, "name" to ename, "type" to tag,
                            "eventSubProcess" to (el.attr("triggeredByEvent") == "true"),
                        ), el, elListeners))
                    }
                    tag in XmlHelpers.BPMN_EVENT_TAGS -> {
                        val (k, v) = XmlHelpers.eventInfo(el)
                        events.add(withElementExtras(
                            linkedMapOf("id" to eid, "name" to ename, "type" to tag, "def" to k, "value" to v),
                            el, elListeners))
                        if (tag == "startEvent" && truthy(el.attr("formKey"))) {
                            ctx.addRef(pkey, "bpmn", ffile, "start-form", "form", el.attr("formKey"))
                        }
                        // message/signal/error/escalation events correlate across models through a
                        // shared named node; throw and catch side carry the direction in the rel.
                        if (k != null && k in listOf("message", "signal", "error", "escalation") && !v.isNullOrEmpty()) {
                            val throwing = tag in listOf("intermediateThrowEvent", "endEvent")
                            val rel = (if (throwing) "throws-" else "catches-") + k
                            ctx.addRef(pkey, "bpmn", ffile, rel, k, correlate(k, v))
                        }
                    }
                    tag in XmlHelpers.BPMN_GW_TAGS -> {
                        gateways.add(withElementExtras(
                            linkedMapOf("id" to eid, "name" to ename, "type" to tag), el, elListeners))
                    }
                    tag in listOf("sendTask", "receiveTask", "manualTask", "task") -> {
                        otherTasks.add(withElementExtras(
                            linkedMapOf("id" to eid, "name" to ename, "type" to tag), el, elListeners))
                        // send/receive tasks reference a <message> definition by id
                        val mref = el.attr("messageRef")
                        if (!mref.isNullOrEmpty() && tag in listOf("sendTask", "receiveTask")) {
                            val rel = if (tag == "sendTask") "throws-message" else "catches-message"
                            ctx.addRef(pkey, "bpmn", ffile, rel, "message", correlate("message", mref))
                        }
                    }
                    tag == "sequenceFlow" -> {
                        val cond = el.textOfDescendant("conditionExpression")
                        val flow = linkedMapOf<String, Any?>(
                            "id" to eid, "from" to el.attr("sourceRef"), "to" to el.attr("targetRef"),
                        )
                        if (truthy(ename)) flow["name"] = ename
                        if (!cond.isNullOrEmpty()) flow["condition"] = cond
                        flows.add(flow)
                        if (!cond.isNullOrEmpty()) {
                            // the flow's own id lets the explorer match this condition to its diagram edge
                            conditions.add(linkedMapOf(
                                "id" to eid, "from" to el.attr("sourceRef"), "to" to el.attr("targetRef"),
                                "condition" to cond,
                            ))
                        }
                    }
                }
            }
            processes.add(info)
        }
        return processes
    }

    // -----------------------------------------------------------------------
    // CMMN
    // -----------------------------------------------------------------------

    /** Service-registry / data-object / agent / template mappings on a CMMN task. */
    private fun cmmnServiceRefs(ctx: Ctx, caseKey: Any?, ffile: String, el: El): LinkedHashMap<String, Any?> {
        val info = LinkedHashMap<String, Any?>()
        val ext = XmlHelpers.extEl(el) ?: return info
        info["serviceTaskType"] = pyOr(el.childText("serviceTaskType"), ext.childText("serviceTaskType"))
        info.putAll(XmlHelpers.readTaskMappings(ctx, caseKey, "cmmn", ffile, el))
        for (tk in listOf("templateKey", "subjectTemplateModelKey", "bodyTemplateModelKey")) {
            val v = ext.childText(tk)
            if (truthy(v)) ctx.addRef(caseKey, "cmmn", ffile, tk, "template", v)
        }
        for (exv in listOf(el.attr("delegateExpression"), el.attr("expression"))) {
            if (exv.isNullOrEmpty()) continue
            for (m in BEAN_RE.findAll(exv)) {
                val b = m.groupValues[1]
                if (b !in Constants.FLOWABLE_CONTEXT) ctx.addRef(caseKey, "cmmn", ffile, "task-delegate", "bean", b)
            }
        }
        return info
    }

    /** A single CMMN plan-item definition (task/stage leaf), with its type-specific refs. */
    @Suppress("UNCHECKED_CAST")
    private fun cmmnDef(ctx: Ctx, caseKey: Any?, ffile: String, el: El): LinkedHashMap<String, Any?> {
        val tag = el.tag
        val d = linkedMapOf<String, Any?>("id" to el.attr("id"), "name" to el.attr("name"), "type" to el.tag)
        when {
            tag == "humanTask" -> {
                d["assignee"] = el.attr("assignee")
                d["candidateGroups"] = el.attr("candidateGroups")
                d["formKey"] = el.attr("formKey")
                ctx.addRef(caseKey, "cmmn", ffile, "humanTask-form", "form", el.attr("formKey"))
                ctx.addAccess(caseKey, "case", "task:${el.attr("id")}", "assign",
                    el.attr("candidateGroups"), pyOr(el.attr("candidateUsers"), el.attr("assignee")))
            }
            tag == "processTask" -> {
                val ref = pyOr(el.textOfDescendant("processRefExpression"), el.attr("processRef"))
                d["processRef"] = ref
                el.attr("sameDeployment")?.let { d["sameDeployment"] = it }
                ctx.addRef(caseKey, "cmmn", ffile, "processTask", "process", ref)
            }
            tag == "caseTask" -> {
                val ref = pyOr(el.textOfDescendant("caseRefExpression"), el.attr("caseRef"))
                d["caseRef"] = ref
                el.attr("sameDeployment")?.let { d["sameDeployment"] = it }
                ctx.addRef(caseKey, "cmmn", ffile, "caseTask", "case", ref)
            }
            tag == "decisionTask" -> {
                val ref = pyOr(el.textOfDescendant("decisionRefExpression"), el.attr("decisionRef"))
                d["decisionRef"] = ref
                el.attr("sameDeployment")?.let { d["sameDeployment"] = it }
                ctx.addRef(caseKey, "cmmn", ffile, "decisionTask", "decision", ref)
            }
            tag in listOf("task", "serviceTask", "humanTaskWithService") -> {
                d.putAll(cmmnServiceRefs(ctx, caseKey, ffile, el))
                d["formKey"] = el.attr("formKey")
                if (truthy(el.attr("formKey"))) {
                    ctx.addRef(caseKey, "cmmn", ffile, "task-form", "form", el.attr("formKey"))
                }
                // assignment on generic tasks carries access too (parity with humanTask)
                if (truthy(el.attr("assignee"))) d["assignee"] = el.attr("assignee")
                if (truthy(el.attr("candidateGroups"))) d["candidateGroups"] = el.attr("candidateGroups")
                ctx.addAccess(caseKey, "case", "task:${el.attr("id")}", "assign",
                    el.attr("candidateGroups"), pyOr(el.attr("candidateUsers"), el.attr("assignee")))
                // CMMN script task: <task flowable:type="script"> with body in a <flowable:field name="script">
                if (el.attr("type") == "script") {
                    d["scriptFormat"] = el.attr("scriptFormat")
                    d["script"] = XmlHelpers.readFields(el)["script"]
                    ScriptValidator.problemDicts(d["script"] as? String, el.attr("scriptFormat"), formatRequired = true,
                        context = ScriptContext.CMMN_SCRIPT_TASK)
                        .ifEmpty { null }?.let { d["problems"] = it }
                    VarHarvest.collectScriptVars(ctx, d["script"] as? String, listOf(caseKey),
                        el.attr("scriptFormat"), el.attr("id"), el.attr("name"), "scriptTask")
                }
                // CMMN external worker task — the topic names the external system's queue
                if (el.attr("type") == "external-worker") {
                    val topic = pyOr(el.attr("topic"), XmlHelpers.readFields(el)["topic"])
                    d["topic"] = topic
                    ctx.addRef(caseKey, "cmmn", ffile, "external-topic", "topic", topic)
                }
            }
        }
        // Forms linked via Design extension elements or pushed in through an in-mapping
        for ((rel, fk) in XmlHelpers.designFormKeys(el)) {
            ctx.addRef(caseKey, "cmmn", ffile, rel, "form", fk)
        }
        // A form key can also arrive as the target of an in-mapping. The mappings themselves are rolled up
        // once per case in [parseCmmn]; here they are only needed to spot that form reference.
        for (fk in XmlHelpers.inoutFormKeys(XmlHelpers.readIoParams(el))) {
            ctx.addRef(caseKey, "cmmn", ffile, "task-form-mapping", "form", fk)
        }
        // A Case Page task exposes tabs via <flowable:page-element>; a tab can render a form
        for (pe in el.iter("page-element")) {
            for (attr in listOf("formKey", "formReference", "formRef", "formKeyExpression")) {
                ctx.addRef(caseKey, "cmmn", ffile, "casePage-form", "form", pe.attr(attr))
            }
        }
        // Event-registry links under the definition's extensionElements
        val dext = XmlHelpers.extEl(el)
        if (dext != null) {
            val ev = dext.childText("eventType")
            if (!ev.isNullOrEmpty()) {
                val rel = if (pyOr(el.attr("type"), d["serviceTaskType"]) in listOf("send-event", "sendEvent"))
                    "sends-event" else "receives-event"
                ctx.addRef(caseKey, "cmmn", ffile, rel, "event", ev)
            }
            ctx.addRef(caseKey, "cmmn", ffile, "trigger-event", "event", dext.childText("triggerEventType"))
            // send/receive via an explicit channel + document event configuration
            ctx.addRef(caseKey, "cmmn", ffile, "via-channel", "channel", dext.childText("channelKey"))
            for (dec in dext.findChildren("documentEventConfiguration")) {
                ctx.addRef(caseKey, "cmmn", ffile, "document-event", "document",
                    dec.attr("definitionKey") ?: dec.childText("definitionKey"))
            }
        }
        val listeners = XmlHelpers.readListeners(el, cmmn = true)
        d["listeners"] = listeners
        XmlHelpers.collectListenerRefs(ctx, caseKey, "cmmn", ffile, listeners, el.attr("id"), el.attr("name"))
        // The modeller's own explanation of this plan item — read at case level only until now.
        el.childText("documentation")?.let { d["documentation"] = it }
        return d
    }

    /** Recursively walk a CMMN stage/plan-fragment, mirroring definitionRef resolution. */
    private fun cmmnWalk(
        ctx: Ctx, caseKey: Any?, ffile: String, stage: El,
        allDefs: Map<String, El>? = null, seen: Set<String>? = null,
    ): LinkedHashMap<String, Any?> {
        val defs = LinkedHashMap<String, El>()
        for (c in stage.children) {
            val id = c.attr("id")
            if (truthy(id)) defs[id!!] = c
        }
        // CMMN resolves a planItem's definitionRef against the whole case, not just this scope.
        val allDefsResolved: Map<String, El> = allDefs ?: run {
            val m = LinkedHashMap<String, El>()
            for (e in iterAll(stage)) {
                val id = e.attr("id")
                if (truthy(id)) m[id!!] = e
            }
            m
        }
        val seenResolved = seen ?: emptySet()
        val children = ArrayList<Any?>()
        val criteria = ArrayList<Any?>()
        val node = linkedMapOf<String, Any?>(
            "id" to stage.attr("id"), "name" to stage.attr("name"), "type" to stage.tag,
            "autoComplete" to stage.attr("autoComplete"), "children" to children, "criteria" to criteria,
        )
        for (pi in stage.findChildren("planItem")) {
            for (crit in pi.children) {
                if (crit.tag in listOf("entryCriterion", "exitCriterion")) {
                    // id joins the criterion to its diagram shape; planItemDef to the plan-tree row
                    criteria.add(linkedMapOf(
                        "id" to crit.attr("id"),
                        "planItem" to pyOr(pi.attr("name"), pi.attr("definitionRef")),
                        "planItemDef" to pi.attr("definitionRef"),
                        "type" to crit.tag, "sentryRef" to crit.attr("sentryRef"),
                    ))
                }
            }
            // item control rules
            val ic = pi.findChild("itemControl")
            val rules = LinkedHashMap<String, Any?>()
            if (ic != null) {
                for (r in listOf("repetitionRule", "requiredRule", "manualActivationRule")) {
                    val rn = ic.findChild(r)
                    if (rn != null) rules[r] = pyOr(rn.childText("condition"), true)
                }
            }
            val ref = pi.attr("definitionRef")
            var target: El? = ref?.let { defs[it] }
            if (target == null) target = ref?.let { allDefsResolved[it] }
            if (target == null) {
                children.add(linkedMapOf(
                    "id" to pi.attr("id"), "name" to pi.attr("name"), "type" to "planItem(?)", "rules" to rules,
                ))
            } else if (target.tag in listOf("stage", "planFragment")) {
                val tid = target.attr("id")
                if (tid in seenResolved) continue  // guard against pathological scope cycles
                val child = cmmnWalk(ctx, caseKey, ffile, target, allDefsResolved, seenResolved + setOfNotNull(tid))
                child["rules"] = rules
                children.add(child)
            } else {
                val d = cmmnDef(ctx, caseKey, ffile, target)
                d["rules"] = rules
                children.add(d)
            }
        }
        return node
    }

    /** `.cmmn` — one entry per `<case>`, with its plan model tree, sentries, milestones and refs. */
    fun parseCmmn(data: ByteArray, ctx: Ctx, ffile: String): List<Map<String, Any?>> {
        val root = AtlasXml.parse(data)
        val cases = ArrayList<Map<String, Any?>>()
        for (case in root.iter("case")) {
            val ckey = case.attr("id")
            val plan = case.findChild("casePlanModel")
            val sentries = ArrayList<Any?>()
            val milestones = ArrayList<Any?>()
            val eventListeners = ArrayList<Any?>()
            val modelRefs = ArrayList<Any?>()
            val ioParameters = ArrayList<Map<String, Any?>>()
            val info = linkedMapOf<String, Any?>(
                "key" to ckey, "name" to case.attr("name"), "file" to ffile,
                "documentation" to case.textOfDescendant("documentation"),
                "initiatorVariableName" to case.attr("initiatorVariableName"),
                "candidateStarterGroups" to case.attr("candidateStarterGroups"),
                "planModel" to (if (plan != null) cmmnWalk(ctx, ckey, ffile, plan) else null),
                "sentries" to sentries, "milestones" to milestones,
                "eventListeners" to eventListeners, "modelRefs" to modelRefs,
                "ioParameters" to ioParameters,
            )
            ctx.addAccess(ckey, "case", "start", "start",
                case.attr("candidateStarterGroups"), case.attr("candidateStarterUsers"))
            if (plan != null) {
                if (truthy(plan.attr("formKey"))) {
                    ctx.addRef(ckey, "cmmn", ffile, "start-form", "form", plan.attr("formKey"))
                }
                // case work form / start form referenced via Design extension elements
                for ((rel, fk) in XmlHelpers.designFormKeys(plan)) {
                    ctx.addRef(ckey, "cmmn", ffile, rel, "form", fk)
                }
            }
            // case-level extension references
            XmlHelpers.collectDesignExtensionRefs(ctx, ckey, "cmmn", "case", ffile, case)
            val ext = XmlHelpers.extEl(case)
            if (ext != null) {
                for ((tag, kind) in listOf(
                    "sla-definition-key" to "sla", "security-policy-model" to "securityPolicy",
                    "eventType" to "event", "channelKey" to "channel",
                )) {
                    val v = ext.childText(tag)
                    if (truthy(v)) {
                        modelRefs.add(linkedMapOf("rel" to tag, "key" to v))
                        ctx.addRef(ckey, "cmmn", ffile, tag, kind, v)
                    }
                }
                val dd = ext.findChild("data-dictionary-model")
                if (dd != null && truthy(dd.attr("key"))) {
                    modelRefs.add(linkedMapOf("rel" to "data-dictionary", "key" to dd.attr("key")))
                    ctx.addRef(ckey, "cmmn", ffile, "data-dictionary", "dataDictionary", dd.attr("key"))
                }
            }
            for (sq in case.iter("caseSequence") + case.iter("processSequence")) {
                val t = sq.text
                if (!t.isNullOrEmpty()) {
                    modelRefs.add(linkedMapOf("rel" to "sequence", "key" to t.trim()))
                    ctx.addRef(ckey, "cmmn", ffile, "uses-sequence", "sequence", t.trim())
                }
            }
            if (plan != null) {
                for (el in iterAll(plan)) {
                    // static model keys (case pages, AI config) + watcher/participant group permissions
                    XmlHelpers.collectDesignExtensionRefs(ctx, ckey, "cmmn", "case", ffile, el)
                    // In/out parameter mappings for the whole plan tree in one pass — process/case tasks,
                    // service-registry / agent / data-object / HTTP tasks and event listeners all use the
                    // same extension elements (see XmlHelpers.readIoParams).
                    val elParams = XmlHelpers.readIoParams(el)
                    if (elParams.isNotEmpty()) {
                        ctx.addParams(
                            ioParameters, ckey, el.attr("id"), el.attr("name"), el.tag, elParams,
                            pyOr(el.attr("type"), el.childText("serviceTaskType")) as? String,
                            XmlHelpers.calleeOf(el),
                        )
                    }
                    when {
                        el.tag == "sentry" -> {
                            val cond = pyOr(el.textOfDescendant("condition"), el.textOfDescendant("ifPart"))
                            val on = el.findChildren("planItemOnPart").map { it.attr("sourceRef") }
                            sentries.add(linkedMapOf("id" to el.attr("id"), "condition" to cond, "onParts" to on))
                        }
                        el.tag == "milestone" -> {
                            milestones.add(linkedMapOf("id" to el.attr("id"), "name" to el.attr("name")))
                        }
                        el.tag == "timerEventListener" -> {
                            eventListeners.add(linkedMapOf(
                                "id" to el.attr("id"), "name" to el.attr("name"), "type" to el.tag,
                                "timer" to el.childText("timerExpression"),
                            ))
                        }
                        el.tag.endsWith("EventListener") -> {
                            val lext = XmlHelpers.extEl(el)
                            val lev = if (lext != null) lext.childText("eventType") else null
                            if (!lev.isNullOrEmpty()) {
                                ctx.addRef(ckey, "cmmn", ffile, "receives-event", "event", lev)
                            }
                            val entry = linkedMapOf<String, Any?>(
                                "id" to el.attr("id"), "name" to el.attr("name"), "type" to el.tag,
                            )
                            if (!lev.isNullOrEmpty()) entry["eventType"] = lev
                            // signal event listener — correlates by signal name across models
                            val sref = el.attr("signalRef")
                            if (!sref.isNullOrEmpty()) {
                                entry["signalRef"] = sref
                                ctx.addRef(ckey, "cmmn", ffile, "catches-signal", "signal", sref)
                            }
                            eventListeners.add(entry)
                        }
                    }
                }
            }
            cases.add(info)
        }
        return cases
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    /** ElementTree `elem.iter()` (no tag): self + every descendant, pre-order. */
    /**
     * Element-level extras every flow node can carry, added only when the model declares them (so a
     * plain task keeps the record shape it always had):
     *  - `documentation` — the modeller's own explanation. Previously read at process/case level only,
     *    which is exactly the text a reader searches for when they don't know the element's id.
     *  - `listeners` — execution/task listeners on *this* element (class, expression, script, event).
     */
    private fun withElementExtras(
        rec: LinkedHashMap<String, Any?>, el: El, listeners: List<Map<String, Any?>>,
    ): LinkedHashMap<String, Any?> {
        el.childText("documentation")?.let { rec["documentation"] = it }
        if (listeners.isNotEmpty()) rec["listeners"] = listeners
        return rec
    }

    private fun iterAll(el: El): List<El> {
        val out = ArrayList<El>()
        fun walk(e: El) {
            out.add(e)
            for (c in e.children) walk(c)
        }
        walk(el)
        return out
    }

    /** Python `a or b`: `a` when truthy, else `b`. */
    private fun pyOr(a: Any?, b: Any?): Any? = if (truthy(a)) a else b

    /** Python truthiness: null/false/0/""/empty-collection are falsy. */
    private fun truthy(v: Any?): Boolean = when (v) {
        null, false -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        is Collection<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        else -> true
    }
}
