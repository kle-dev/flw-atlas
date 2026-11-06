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
                // The process's own child: `textOfDescendant` took the first `<documentation>` anywhere below,
                // so a process without prose of its own was described by its first task's note — a TODO in a
                // user task became the process description in the report and the explorer. Each element keeps
                // its own documentation (see withElementExtras).
                "documentation" to proc.childText("documentation"),
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

            // Work offers a definition in its create dialog to a non-admin user only when the user or one of
            // their groups is a candidate starter — users count as much as groups, and were access-only.
            proc.attr("candidateStarterUsers")?.trim()?.ifEmpty { null }?.let { info["candidateStarterUsers"] = it }
            val listeners = XmlHelpers.readListeners(proc)
            info["listeners"] = listeners
            XmlHelpers.collectListenerRefs(ctx, pkey, "bpmn", ffile, listeners)
            ctx.addAccess(pkey, "process", "start", "start",
                proc.attr("candidateStarterGroups"), proc.attr("candidateStarterUsers"))
            // The sub-process each element sits in. A user task inside an event sub-process exists only once
            // that sub-process's event fires, so "the process's user tasks, in order" needs to know which
            // tasks are not on the main path at all.
            val enclosing = enclosingSubProcess(proc)

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

            // flow ids that some gateway/activity names as its default path (marked onto `flows` below)
            val defaultFlows = HashSet<String>()
            for (el in iterAll(proc)) {
                val tag = el.tag
                val eid = el.attr("id")
                val ename = el.attr("name")
                if (tag != "sequenceFlow") el.attr("default")?.ifEmpty { null }?.let { defaultFlows.add(it) }
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
                            val rel = if (XmlHelpers.eventRole(el) == "send") "sends-event" else "receives-event"
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
                    val rec = linkedMapOf<String, Any?>(
                        "activity" to eid, "collection" to mi.attr("collection"),
                        "elementVariable" to mi.attr("elementVariable"),
                        "cardinality" to mi.childText("loopCardinality"),
                        "sequential" to mi.attr("isSequential"),
                    )
                    // the one thing that lets the activity move on before every instance is done
                    mi.childText("completionCondition")?.let { rec["completionCondition"] = it }
                    multiInstance.add(rec)
                }

                // In/out parameter mappings, read once for every element: Flowable allows them on call
                // activities, case/service-registry/agent/data-object/HTTP/init-variables service tasks,
                // script tasks, receive tasks and event-registry start/boundary events, and they all use
                // the same handful of extension elements (see XmlHelpers.readIoParams).
                val elParams = XmlHelpers.readIoParams(el)
                if (elParams.isNotEmpty()) {
                    ctx.addParams(ioParameters, pkey, eid, ename, tag, elParams, el.attr("type"),
                        XmlHelpers.callee(el))
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
                        // the common case keeps the minimal five-key shape. Candidate users likewise: a
                        // task offered to named users only is closed to everyone else, which nothing on
                        // the record said while they went to the access list alone.
                        for (a in listOf("candidateUsers", "dueDate", "priority", "category")) {
                            el.attr(a)?.ifEmpty { null }?.let { ut[a] = it }
                        }
                        // the SLA that times this task and the policy that gates it, when it names its own
                        ut.putAll(XmlHelpers.governance(el))
                        enclosing[eid]?.let { ut["parent"] = it }
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
                            "type" to el.attr("type"),
                            // Either spelling, in the engine's order: ServiceTaskXMLConverter reads
                            // `resultVariableName` and falls back to `resultVariable`, the form the BPMN user
                            // guide documents — so hand-written models are the ones that use it.
                            "resultVariable" to pyOr(el.attr("resultVariableName"), el.attr("resultVariable")),
                        )
                        serviceTasks.add(withElementExtras(st, el, elListeners))
                        ctx.addRef(pkey, "bpmn", ffile, "serviceTask-class", "class", st["class"])
                        // Both `delegateExpression` and `expression` may reference a bean — a bean-only
                        // `flowable:expression="${myBean}"` has no method call, so the whole-file
                        // METHOD_CALL harvest never sees it either. Which attribute it was travels in the
                        // rel: a delegate expression can only name a bean, an expression may as well start
                        // with a variable (`${requesterData.getName()}`), and the resolver decides which.
                        for (exAttr in listOf("delegateExpression", "expression")) {
                            val exv = st[exAttr] as? String
                            if (exv.isNullOrEmpty()) continue
                            val rel = if (exAttr == "expression") "serviceTask-expression" else "serviceTask-delegate"
                            for (m in BEAN_RE.findAll(exv)) {
                                val b = m.groupValues[1]
                                // `${true}` is a literal, not a bean called true
                                if (b !in Constants.FLOWABLE_CONTEXT && b !in Constants.JAVA_LITERALS)
                                    ctx.addRef(pkey, "bpmn", ffile, rel, "bean", b)
                            }
                        }
                        val type = el.attr("type")
                        if (type == "http") {
                            val f = XmlHelpers.readFields(el)
                            if (truthy(f["requestUrl"])) {
                                ctx.addRestCall(pkey, ffile, eid, pyOr(f["requestMethod"], "GET"), f["requestUrl"], "http-task")
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
                        // What can turn a failure into a BPMN error — the only thing an error boundary or an
                        // error event subprocess catches: a `mapException`, and an HTTP task's own response
                        // handler. Written only when present; a triggerable task waits for its trigger.
                        val ext = XmlHelpers.extEl(el)
                        if (ext != null && ext.findChildren("mapException").isNotEmpty()) st["mapsExceptions"] = true
                        if (type == "http" && ext?.findChild("httpResponseHandler") != null) st["responseHandler"] = true
                        if (el.attr("triggerable") == "true") st["triggerable"] = "true"
                        // Names only: a password written as a literal field is a finding, its value is not data.
                        SecretScan.secretFields(XmlHelpers.readLiteralFields(el)).takeIf { it.isNotEmpty() }
                            ?.let { st["secretFields"] = it }
                        XmlHelpers.resultVariableParam("resultVariable", st["resultVariable"] as? String)
                            ?.let { ctx.addParams(ioParameters, pkey, eid, ename, tag, listOf(it), type,
                                XmlHelpers.callee(el)) }
                    }
                    tag == "scriptTask" -> {
                        val body = el.childText("script")
                        // the mirror image of a service task: ScriptTaskXMLConverter reads `resultVariable`
                        // and falls back to `resultVariableName`
                        val rv = pyOr(el.attr("resultVariable"), el.attr("resultVariableName")) as String?
                        val st = linkedMapOf<String, Any?>(
                            "id" to eid, "name" to ename, "format" to el.attr("scriptFormat"),
                            "script" to body, "resultVariable" to rv,
                        )
                        ScriptValidator.problemDicts(body, el.attr("scriptFormat"), formatRequired = true,
                            context = ScriptContext.BPMN_SCRIPT_TASK)
                            .ifEmpty { null }?.let { st["problems"] = it }
                        scriptTasks.add(withElementExtras(st, el, elListeners))
                        VarHarvest.collectScriptVars(ctx, body, listOf(pkey), el.attr("scriptFormat"),
                            eid, ename, "scriptTask")
                        ctx.addVar(pkey, rv)
                        XmlHelpers.resultVariableParam("resultVariable", rv)
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
                    tag in XmlHelpers.BPMN_SUB_PROCESS_TAGS -> {
                        val sp = linkedMapOf<String, Any?>(
                            "id" to eid, "name" to ename, "type" to tag,
                            "eventSubProcess" to (el.attr("triggeredByEvent") == "true"),
                        )
                        // nested sub-processes chain up, so "is this task inside an event sub-process"
                        // can be answered for any depth
                        enclosing[eid]?.let { sp["parent"] = it }
                        subProcesses.add(withElementExtras(sp, el, elListeners))
                    }
                    tag in XmlHelpers.BPMN_EVENT_TAGS -> {
                        val (k, v) = XmlHelpers.eventInfo(el)
                        val ev = linkedMapOf<String, Any?>(
                            "id" to eid, "name" to ename, "type" to tag, "def" to k, "value" to v)
                        // A boundary event is meaningless without the activity it hangs on — record the
                        // host, and whether triggering it cancels that activity.
                        if (tag == "boundaryEvent") {
                            el.attr("attachedToRef")?.ifEmpty { null }?.let { ev["attachedTo"] = it }
                            el.attr("cancelActivity")?.ifEmpty { null }?.let { ev["cancelActivity"] = it }
                        }
                        events.add(withElementExtras(ev, el, elListeners))
                        if (tag == "startEvent" && truthy(el.attr("formKey"))) {
                            ctx.addRef(pkey, "bpmn", ffile, "start-form", "form", el.attr("formKey"))
                        }
                        // message/signal/error/escalation events correlate across models through a
                        // shared named node; throw and catch side carry the direction in the rel.
                        if (k != null && k in listOf("message", "signal", "error", "escalation") && !v.isNullOrEmpty()) {
                            val throwing = tag in listOf("intermediateThrowEvent", "endEvent")
                            val rel = (if (throwing) "throws-" else "catches-") + k
                            ctx.addRef(pkey, "bpmn", ffile, rel, k, correlate(k, v))
                            // the name the shared node is keyed by — `value` is the definition's id, which
                            // another model's definition of the same signal need not share
                            ev["ref"] = correlate(k, v)
                        }
                    }
                    tag in XmlHelpers.BPMN_GW_TAGS -> {
                        val gw = linkedMapOf<String, Any?>("id" to eid, "name" to ename, "type" to tag)
                        // the flow taken when no condition matches — as much a part of the gateway's
                        // logic as the conditions themselves
                        el.attr("default")?.ifEmpty { null }?.let { gw["default"] = it }
                        gateways.add(withElementExtras(gw, el, elListeners))
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
            // mark the flow a gateway/activity declared as its default path
            if (defaultFlows.isNotEmpty()) {
                for (f in flows) {
                    @Suppress("UNCHECKED_CAST") val fm = f as LinkedHashMap<String, Any?>
                    if (fm["id"] in defaultFlows) fm["default"] = true
                }
            }
            // Lanes: who works which part of the process. Only painted in the diagram until now —
            // a lane's name was neither searchable nor stated anywhere in the report.
            val lanes = ArrayList<Any?>()
            for (ls in proc.findChildren("laneSet")) {
                for (lane in ls.findChildren("lane")) {
                    lanes.add(linkedMapOf(
                        "id" to lane.attr("id"), "name" to lane.attr("name"),
                        "elements" to lane.findChildren("flowNodeRef")
                            .mapNotNull { it.text?.trim() }.filter { it.isNotEmpty() },
                    ))
                }
            }
            if (lanes.isNotEmpty()) info["lanes"] = lanes
            processes.add(info)
        }
        return processes
    }

    // -----------------------------------------------------------------------
    // CMMN
    // -----------------------------------------------------------------------

    /** The Java implementation and the service-registry / data-object / agent / template mappings on a CMMN task. */
    private fun cmmnServiceRefs(ctx: Ctx, caseKey: Any?, ffile: String, el: El): LinkedHashMap<String, Any?> {
        val info = LinkedHashMap<String, Any?>()
        // The implementation first: the engine reads `flowable:class`, `delegateExpression` and `expression`
        // straight off the `<task>` (TaskXmlConverter), so a hand-written `<task flowable:type="java"
        // flowable:class="…"/>` has no extension elements at all — and returning on a missing
        // `<extensionElements>` used to drop its code link along with it. Design always writes the element,
        // which is why only hand-written models lost theirs.
        ctx.addRef(caseKey, "cmmn", ffile, "task-class", "class", el.attr("class"))
        for ((exAttr, exv) in listOf("delegateExpression" to el.attr("delegateExpression"), "expression" to el.attr("expression"))) {
            if (exv.isNullOrEmpty()) continue
            val rel = if (exAttr == "expression") "task-expression" else "task-delegate"
            for (m in BEAN_RE.findAll(exv)) {
                val b = m.groupValues[1]
                if (b !in Constants.FLOWABLE_CONTEXT && b !in Constants.JAVA_LITERALS) ctx.addRef(caseKey, "cmmn", ffile, rel, "bean", b)
            }
        }
        val ext = XmlHelpers.extEl(el) ?: return info
        info["serviceTaskType"] = pyOr(el.childText("serviceTaskType"), ext.childText("serviceTaskType"))
        info.putAll(XmlHelpers.readTaskMappings(ctx, caseKey, "cmmn", ffile, el))
        for (tk in listOf("templateKey", "subjectTemplateModelKey", "bodyTemplateModelKey")) {
            val v = ext.childText(tk)
            if (truthy(v)) ctx.addRef(caseKey, "cmmn", ffile, tk, "template", v)
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
                // only when set (the record keeps its shape otherwise): a task offered to named users
                // only is closed to everyone else, and the access list was the only place that said so
                el.attr("candidateUsers")?.trim()?.ifEmpty { null }?.let { d["candidateUsers"] = it }
                // the SLA that times this task and the policy that gates it, when it names its own
                d.putAll(XmlHelpers.governance(el))
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
                // Which code the task runs, on the record as on a BPMN service task: the edge alone said that
                // the case calls a bean, not which of its tasks does. Only when set, so the record of every
                // other task keeps its shape.
                for (a in listOf("class", "expression", "delegateExpression")) {
                    el.attr(a)?.trim()?.ifEmpty { null }?.let { d[a] = it }
                }
                cmmnResultVariable(el)?.let { d["resultVariable"] = it }
                SecretScan.secretFields(XmlHelpers.readLiteralFields(el)).takeIf { it.isNotEmpty() }
                    ?.let { d["secretFields"] = it }
                d["formKey"] = el.attr("formKey")
                if (truthy(el.attr("formKey"))) {
                    ctx.addRef(caseKey, "cmmn", ffile, "task-form", "form", el.attr("formKey"))
                }
                // assignment on generic tasks carries access too (parity with humanTask)
                if (truthy(el.attr("assignee"))) d["assignee"] = el.attr("assignee")
                if (truthy(el.attr("candidateGroups"))) d["candidateGroups"] = el.attr("candidateGroups")
                if (truthy(el.attr("candidateUsers"))) d["candidateUsers"] = el.attr("candidateUsers")
                // What a generic `<task>` is at runtime: `casePage` creates no task at all — Work shows it
                // as an entry in the case's navigation — so "task X" would send a reader to a task list
                // where it never appears. Service-task flavours keep their `serviceTaskType`.
                if (tag == "task") el.attr("type")?.trim()?.ifEmpty { null }?.let { d["taskType"] = it }
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
            // A start form on a plan item definition is what its action button opens before the item
            // starts — on a user event listener, a reactivation listener and a manually activated task or
            // process task alike (Design writes `startformkey` on any of them). Recorded on the item so it
            // is not mistaken for the case's own start form, which the edge alone cannot tell apart.
            if (rel == "start-form") d["startFormKey"] = fk
        }
        // Work turns an enabled (manual-activation) plan item and an available user event listener into an
        // action button. It is named after the plan item unless the model names it, and it is offered only
        // to the groups and users the model lists — the facts that decide whether a user can find and press it.
        XmlHelpers.extEl(el)?.let { ext ->
            for ((tag2, field) in listOf(
                "manual-activation-name" to "actionName", "event-listener-name" to "actionName",
                "manual-activation-permission-groups" to "actionGroups", "event-listener-permission-groups" to "actionGroups",
                "manual-activation-permission-users" to "actionUsers", "event-listener-permission-users" to "actionUsers",
            )) ext.childText(tag2)?.let { d[field] = it }
            // A reactivation listener's button is offered only for this long after the case ended.
            if (tag == "eventListener") ext.childText("event-listener-timer-expression")?.let { d["offeredFor"] = it }
        }
        if (tag.endsWith("EventListener") || tag == "eventListener") {
            // While this is false the listener is *unavailable*: no button, nothing to trigger. The engine
            // re-checks it on every evaluation, so the button comes and goes with the variables it reads.
            el.attr("availableCondition")?.trim()?.ifEmpty { null }?.let { d["availableCondition"] = it }
        }
        when (tag) {
            // what a timer listener waits for, on the plan-tree record itself
            "timerEventListener" -> el.childText("timerExpression")?.let { d["timer"] = it }
            // A generic `<eventListener>` is typed by its `flowable:eventType` *attribute* — `variable`,
            // `signal`, `intent`, `reactivate` — not to be confused with the event-registry `<eventType>`
            // child element (see XmlHelpers.eventKeys, recorded as `eventType`). Each waits for something
            // other than a click, and the reactivation listener is the case's *Reopen* button.
            "eventListener" -> {
                el.attr("eventType")?.trim()?.ifEmpty { null }?.let { d["listenerType"] = it }
                for (a in listOf("variableName", "variableChangeType", "signalRef")) {
                    el.attr(a)?.trim()?.ifEmpty { null }?.let { d[a] = it }
                }
            }
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
                val rel = if (XmlHelpers.eventRole(el, d["serviceTaskType"]) == "send") "sends-event" else "receives-event"
                ctx.addRef(caseKey, "cmmn", ffile, rel, "event", ev)
            }
            d.putAll(XmlHelpers.eventKeys(el, d["serviceTaskType"]))
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

    /**
     * The variable a CMMN task stores its result in. The engine reads `flowable:resultVariableName` on a Java
     * task and on a script task only (TaskXmlConverter); on any other task type the attribute does nothing,
     * so a write credited to it there would be a write that never happens.
     */
    private fun cmmnResultVariable(el: El): String? =
        if (el.tag == "task" && el.attr("type") in setOf("java", "script")) el.attr("resultVariableName")?.trim()?.ifEmpty { null }
        else null

    /**
     * The two attributes that decide a stage's or milestone's place in Work's stage overview — the bar a
     * user (and an agent asked to "bring the case to stage 5") counts stages by. `displayOrder` orders it,
     * `includeInStageOverview="false"` hides the element from it. Recorded only when the model sets them.
     */
    private fun stageOverviewAttrs(el: El, rec: MutableMap<String, Any?>) {
        el.attr("displayOrder")?.trim()?.ifEmpty { null }?.let { rec["displayOrder"] = it }
        el.attr("includeInStageOverview")?.trim()?.ifEmpty { null }?.let { rec["includeInStageOverview"] = it }
    }

    /**
     * One entry or exit criterion. `planItemId` joins it to the plan item it sits on — the definition id
     * alone (`planItemDef`) is shared by every plan item that references the same definition, which is
     * legal CMMN, and gave each of them the other's criteria. `exitEventType` / `exitType` say what an exit
     * does: on a stage, `complete`/`forceComplete` ends it as *completed* (sentries waiting for "completes"
     * fire), where a plain exit only fires "is exited"; `exitType` limits which instances are ended.
     */
    private fun criterion(crit: El, pi: El?): LinkedHashMap<String, Any?> {
        val rec = linkedMapOf<String, Any?>("id" to crit.attr("id"))
        if (pi != null) {
            rec["planItem"] = pyOr(pi.attr("name"), pi.attr("definitionRef"))
            rec["planItemDef"] = pi.attr("definitionRef")
        }
        rec["type"] = crit.tag
        rec["sentryRef"] = crit.attr("sentryRef")
        pi?.attr("id")?.ifEmpty { null }?.let { rec["planItemId"] = it }
        for (a in listOf("exitEventType", "exitType")) crit.attr(a)?.trim()?.ifEmpty { null }?.let { rec[a] = it }
        return rec
    }

    /**
     * A plan item's item-control rules: the three CMMN rules (condition text, or `true` when it has none),
     * plus the Flowable details that change what they mean at runtime — how many instances a repetition
     * runs at once or over which collection, whether the item holds its stage open (`parentCompletionRule`,
     * `completionNeutralRule`), and whether a manual activation is for the case's AI agent.
     */
    private fun itemRules(ic: El?): LinkedHashMap<String, Any?> {
        val rules = LinkedHashMap<String, Any?>()
        if (ic == null) return rules
        for (r in listOf("repetitionRule", "requiredRule", "manualActivationRule")) {
            val rn = ic.findChild(r)
            if (rn != null) rules[r] = pyOr(rn.childText("condition"), true)
        }
        ic.findChild("repetitionRule")?.let { rep ->
            for (a in listOf("maxInstanceCount", "collectionVariable", "elementVariable")) {
                rep.attr(a)?.trim()?.ifEmpty { null }?.let { rules[a] = it }
            }
        }
        ic.findChild("manualActivationRule")?.let { XmlHelpers.extEl(it) }?.childText("aiActivationType")
            ?.let { rules["aiActivationType"] = it }
        XmlHelpers.extEl(ic)?.let { ext ->
            ext.findChild("parentCompletionRule")?.let { rules["parentCompletionRule"] = it.attr("type")?.trim()?.ifEmpty { null } ?: true }
            ext.findChild("completionNeutralRule")?.let { rules["completionNeutralRule"] = pyOr(it.childText("condition"), true) }
        }
        return rules
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
        stageOverviewAttrs(stage, node)
        // An autoComplete decided at runtime: the stage completes by itself only while this holds.
        stage.attr("autoCompleteCondition")?.trim()?.ifEmpty { null }?.let { node["autoCompleteCondition"] = it }
        // A stage's own start form — the dialog its manual-activation button opens. The case plan model's
        // forms are the case's and are recorded by [parseCmmn].
        if (allDefs != null) {
            for ((rel, fk) in XmlHelpers.designFormKeys(stage)) {
                ctx.addRef(caseKey, "cmmn", ffile, rel, "form", fk)
                if (rel == "start-form") node["startFormKey"] = fk
            }
            XmlHelpers.extEl(stage)?.let { ext ->
                for ((tag2, field) in listOf(
                    "manual-activation-name" to "actionName", "manual-activation-permission-groups" to "actionGroups",
                    "manual-activation-permission-users" to "actionUsers",
                )) ext.childText(tag2)?.let { node[field] = it }
            }
        }
        for (pi in stage.findChildren("planItem")) {
            // id joins the criterion to its diagram shape; planItemId (else planItemDef) to the plan-tree row
            for (crit in pi.children) {
                if (crit.tag in listOf("entryCriterion", "exitCriterion")) criteria.add(criterion(crit, pi))
            }
            val rules = itemRules(pi.findChild("itemControl"))
            val ref = pi.attr("definitionRef")
            var target: El? = ref?.let { defs[it] }
            if (target == null) target = ref?.let { allDefsResolved[it] }
            // A sentry's onPart names the *plan item* (`planItemhumanTask1`), the tree is keyed by the
            // definition (`humanTask1`): without the plan item's id on the record, "after X completes"
            // could not be said by name. Only when the two differ, which in a Design export is always.
            // The plan item's own name likewise, when it differs from the definition's: Work names the
            // running instance (its task, its action button, its entry in the stage bar) after it.
            fun planItemId(rec: MutableMap<String, Any?>) {
                val pid = pi.attr("id")
                if (truthy(pid) && pid != rec["id"]) rec["planItemId"] = pid
                val pname = pi.attr("name")?.trim()
                if (!pname.isNullOrEmpty() && pname != (rec["name"] as? String)?.trim()) rec["planItemName"] = pname
            }
            if (target == null) {
                children.add(linkedMapOf(
                    "id" to pi.attr("id"), "name" to pi.attr("name"), "type" to "planItem(?)", "rules" to rules,
                ))
            } else if (target.tag in listOf("stage", "planFragment")) {
                val tid = target.attr("id")
                if (tid in seenResolved) continue  // guard against pathological scope cycles
                val child = cmmnWalk(ctx, caseKey, ffile, target, allDefsResolved, seenResolved + setOfNotNull(tid))
                child["rules"] = rules
                planItemId(child)
                children.add(child)
            } else {
                val d = cmmnDef(ctx, caseKey, ffile, target)
                d["rules"] = rules
                planItemId(d)
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
                // The case's own prose, or the plan model's: the engine writes the case's documentation on
                // `<case>` (CaseExport), Design keeps the plan model shape's on `<casePlanModel>`. Never a
                // descendant's — the first one below the case was a sentry's or a task's.
                "documentation" to (case.childText("documentation") ?: plan?.childText("documentation")),
                "initiatorVariableName" to case.attr("initiatorVariableName"),
                "candidateStarterGroups" to case.attr("candidateStarterGroups"),
                "planModel" to (if (plan != null) cmmnWalk(ctx, ckey, ffile, plan) else null),
                "sentries" to sentries, "milestones" to milestones,
                "eventListeners" to eventListeners, "modelRefs" to modelRefs,
                "ioParameters" to ioParameters,
            )
            val overview = HashMap<String, Int>()   // stage/milestone id → place in the stage overview, below
            // see the same key on a process: users count as much as groups for who may start it
            case.attr("candidateStarterUsers")?.trim()?.ifEmpty { null }?.let { info["candidateStarterUsers"] = it }
            // The case's own lifecycle listeners (`<flowable:caseLifecycleListener>` on `<case>`): code that runs
            // when the whole case changes state. Only plan items' listeners were read, so a listener bean on
            // the case had no edge and its class no `listener` role. Only when declared, like the case's
            // other optional keys.
            val caseListeners = XmlHelpers.readListeners(case, cmmn = true)
            if (caseListeners.isNotEmpty()) {
                info["listeners"] = caseListeners
                XmlHelpers.collectListenerRefs(ctx, ckey, "cmmn", ffile, caseListeners, ckey, case.attr("name"))
            }
            ctx.addAccess(ckey, "case", "start", "start",
                case.attr("candidateStarterGroups"), case.attr("candidateStarterUsers"))
            if (plan != null) {
                if (truthy(plan.attr("formKey"))) {
                    ctx.addRef(ckey, "cmmn", ffile, "start-form", "form", plan.attr("formKey"))
                }
                // case work form / start form referenced via Design extension elements
                val planForms = XmlHelpers.designFormKeys(plan)
                for ((rel, fk) in planForms) {
                    ctx.addRef(ckey, "cmmn", ffile, rel, "form", fk)
                }
                // The case's *own* forms, on the record. Every plan item's start form is a `start-form` edge
                // of the case too (the reference belongs to the case model), so the edges alone cannot say
                // which one the create dialog shows and which one an action button opens.
                (plan.attr("formKey")?.trim()?.ifEmpty { null } ?: planForms.firstOrNull { it.first == "start-form" }?.second)
                    ?.let { info["startFormKey"] = it }
                planForms.firstOrNull { it.first == "work-form" }?.let { info["workFormKey"] = it.second }
                // A custom case view is where the case's pages and its own navigation live; Work links to it
                // from the case header, for the view's permission groups. Only the first view is kept: Work
                // takes the first one the user's groups allow, and one is enough to say that a view exists.
                XmlHelpers.extEl(plan)?.findChildren("custom-view-element")
                    ?.firstOrNull { !it.attr("customViewKey").isNullOrBlank() }?.let { v ->
                        info["customViewKey"] = v.attr("customViewKey")!!.trim()
                        v.attr("permissionGroups")?.trim()?.ifEmpty { null }?.let { info["customViewGroups"] = it }
                    }
                // An exit criterion on the case plan model ends the whole case — every stage, every open
                // task — and the plan-tree walk only reads criteria on plan items. Kept on the plan model's
                // own record, where the XML has it.
                @Suppress("UNCHECKED_CAST")
                val pm = info["planModel"] as? MutableMap<String, Any?>
                plan.findChildren("exitCriterion").map { criterion(it, null) }.takeIf { it.isNotEmpty() }
                    ?.let { pm?.set("exitCriteria", it) }
                // Where Work's stage overview puts each stage and milestone before `displayOrder` and end
                // times reorder it: every stage of the case in document order (nested ones included), then
                // every milestone. It is the tie-break between entries that share a `displayOrder` or have
                // none, and it is not the plan-item order the tree is in.
                for (tag in listOf("stage", "milestone")) for (e in plan.iter(tag)) {
                    e.attr("id")?.ifEmpty { null }?.let { overview.putIfAbsent(it, overview.size) }
                }
                fun placeInOverview(node: MutableMap<String, Any?>) {
                    @Suppress("UNCHECKED_CAST")
                    for (ch in (node["children"] as? List<*>).orEmpty().mapNotNull { it as? MutableMap<String, Any?> }) {
                        if (ch["type"] == "stage" || ch["type"] == "milestone") overview[ch["id"]]?.let { ch["overviewIndex"] = it }
                        if (ch.containsKey("children")) placeInOverview(ch)
                    }
                }
                pm?.let(::placeInOverview)
            }
            // case-level extension references
            XmlHelpers.collectDesignExtensionRefs(ctx, ckey, "cmmn", "case", ffile, case)
            val ext = XmlHelpers.extEl(case)
            // The case's SLA and policy may sit on `<case>` or on its plan model, which is where Design writes
            // the policy and where the platform reads it (AbstractPlatformSecurityService). The walk over the
            // plan tree below records the same reference, with the same kind and relation, so the two collapse
            // into one; what this adds is its place among the case's own model references, which is what
            // says "this policy governs the case" on its page.
            val planExt = plan?.let { XmlHelpers.extEl(it) }
            for ((tag, kind) in listOf("sla-definition-key" to "sla", "security-policy-model" to "securityPolicy")) {
                for (v in listOfNotNull(ext?.childText(tag), planExt?.childText(tag)).distinct()) {
                    modelRefs.add(linkedMapOf("rel" to tag, "key" to v))
                    ctx.addRef(ckey, "cmmn", ffile, tag, kind, v)
                }
            }
            if (ext != null) {
                for ((tag, kind) in listOf("eventType" to "event", "channelKey" to "channel")) {
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
                    // same extension elements (see XmlHelpers.readIoParams). A Java or script task's result
                    // variable is an attribute, not an element, and joins them here: this loop is where the
                    // case's rollup lives, and without it the write had no writer — the variable was only
                    // "declared / mapped", the same name judged differently from its BPMN twin.
                    val elParams = XmlHelpers.readIoParams(el) +
                        listOfNotNull(XmlHelpers.resultVariableParam("resultVariable", cmmnResultVariable(el)))
                    if (elParams.isNotEmpty()) {
                        ctx.addParams(
                            ioParameters, ckey, el.attr("id"), el.attr("name"), el.tag, elParams,
                            pyOr(el.attr("type"), el.childText("serviceTaskType")) as? String,
                            XmlHelpers.callee(el),
                        )
                    }
                    when {
                        el.tag == "sentry" -> {
                            // `ifPart/condition` first: Design writes its condition builder as
                            // `<flowable:condition>` elements under extensionElements, *before* the ifPart, so
                            // "the first descendant called condition" was an empty builder node and the real
                            // condition was lost on every sentry built that way.
                            val cond = pyOr(pyOr(el.findChild("ifPart")?.childText("condition"),
                                el.textOfDescendant("condition")), el.textOfDescendant("ifPart"))
                            val parts = el.findChildren("planItemOnPart")
                            val on = parts.map { it.attr("sourceRef") }
                            val sentry = linkedMapOf<String, Any?>("id" to el.attr("id"), "condition" to cond, "onParts" to on)
                            // `onEvent`: a satisfied on-part is not remembered, so the event and the condition
                            // have to coincide — a later variable change does not fire it any more.
                            el.attr("triggerMode")?.trim()?.ifEmpty { null }?.let { sentry["triggerMode"] = it }
                            // Which transition of the source fires the sentry — `complete` almost always, but
                            // `occur` (a milestone or user event listener) and `exit`/`terminate` say something
                            // quite different about what has to happen first. Only when the model says it.
                            val events = parts.mapNotNull { p ->
                                p.childText("standardEvent")?.let { linkedMapOf("source" to p.attr("sourceRef"), "event" to it) }
                            }
                            if (events.isNotEmpty()) sentry["events"] = events
                            sentries.add(sentry)
                        }
                        el.tag == "milestone" -> {
                            val ms = linkedMapOf<String, Any?>("id" to el.attr("id"), "name" to el.attr("name"))
                            stageOverviewAttrs(el, ms)
                            overview[el.attr("id")]?.let { ms["overviewIndex"] = it }
                            milestones.add(ms)
                        }
                        el.tag == "timerEventListener" -> {
                            eventListeners.add(linkedMapOf(
                                "id" to el.attr("id"), "name" to el.attr("name"), "type" to el.tag,
                                "timer" to el.childText("timerExpression"),
                            ))
                        }
                        // `eventListener` itself too: the generic listener (and the typed variable, signal and
                        // reactivation listeners Design writes as one) used to fall outside the suffix test,
                        // so a signal listener's signal was never correlated with whoever throws it.
                        el.tag.endsWith("EventListener") || el.tag == "eventListener" -> {
                            val lext = XmlHelpers.extEl(el)
                            val lev = if (lext != null) lext.childText("eventType") else null
                            if (!lev.isNullOrEmpty()) {
                                ctx.addRef(ckey, "cmmn", ffile, "receives-event", "event", lev)
                            }
                            val entry = linkedMapOf<String, Any?>(
                                "id" to el.attr("id"), "name" to el.attr("name"), "type" to el.tag,
                            )
                            if (!lev.isNullOrEmpty()) entry["eventType"] = lev
                            if (el.tag == "eventListener") el.attr("eventType")?.trim()?.ifEmpty { null }?.let { entry["listenerType"] = it }
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
        // engine-behaviour flags, only when the model departs from the default: async continuation
        // and the expression that lets the engine skip the element entirely
        for (a in listOf("async", "asyncLeave", "skipExpression")) {
            el.attr(a)?.ifEmpty { null }?.takeIf { it != "false" }?.let { rec[a] = it }
        }
        // `exclusive` is the mirror image: the engine defaults it to true and the exporter writes it
        // only to say false, so the value worth carrying is the opt-out, not the presence.
        el.attr("exclusive")?.ifEmpty { null }?.takeIf { it == "false" }?.let { rec["exclusive"] = it }
        // The retry policy is a child element under extensionElements, not an attribute.
        XmlHelpers.extEl(el)?.childText("failedJobRetryTimeCycle")?.let { rec["retryTimeCycle"] = it }
        if (listeners.isNotEmpty()) rec["listeners"] = listeners
        // the event it publishes or consumes and the channel it uses — which element does, not only
        // that the model does
        rec.putAll(XmlHelpers.eventKeys(el))
        return rec
    }

    /** Element id → the id of the nearest sub-process around it, for every element that sits in one. */
    private fun enclosingSubProcess(proc: El): Map<String, String> {
        val out = HashMap<String, String>()
        fun walk(e: El, sp: String?) {
            for (c in e.children) {
                val id = c.attr("id")
                if (sp != null && !id.isNullOrEmpty()) out[id] = sp
                walk(c, if (c.tag in XmlHelpers.BPMN_SUB_PROCESS_TAGS && !id.isNullOrEmpty()) id else sp)
            }
        }
        walk(proc, null)
        return out
    }

    /** ElementTree `elem.iter()` (no tag): self + every descendant, pre-order. */
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
