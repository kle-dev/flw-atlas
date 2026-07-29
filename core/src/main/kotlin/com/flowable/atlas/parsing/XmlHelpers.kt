package com.flowable.atlas.parsing

import com.flowable.atlas.graph.Ctx
import com.flowable.atlas.parsing.AtlasXml.El

/**
 * BPMN/CMMN element helpers — a port of the Flowable-Design extension-element readers in
 * `flowable_atlas.py` (`ext_el`, `design_form_keys`, `inout_form_keys`, `read_fields`, `read_in_out`,
 * `read_listeners`, `collect_listener_refs`, `_event_info` + the BPMN tag/def name lists, ~lines
 * 159-356). All navigation is by local name via [AtlasXml].
 */
object XmlHelpers {

    /** Design stores form references as extension elements; tag → reference relation. */
    val DESIGN_FORM_TAGS: Map<String, String> = linkedMapOf(
        "workformkey" to "work-form", "startformkey" to "start-form", "formkey" to "form",
    )

    val BPMN_EVENT_DEFS = listOf(
        "timerEventDefinition", "messageEventDefinition", "signalEventDefinition",
        "errorEventDefinition", "conditionalEventDefinition", "escalationEventDefinition",
        "terminateEventDefinition", "compensateEventDefinition",
    )
    val BPMN_EVENT_TAGS = listOf(
        "startEvent", "endEvent", "intermediateCatchEvent", "intermediateThrowEvent", "boundaryEvent",
    )
    val BPMN_GW_TAGS = listOf(
        "exclusiveGateway", "parallelGateway", "inclusiveGateway", "eventBasedGateway", "complexGateway",
    )

    fun extEl(el: El): El? = el.findChild("extensionElements")

    /** `[(rel, formKey)]` for forms referenced via Design extension elements under `el`.
     *  Matched case-insensitively — Design has emitted both `<flowable:formkey>` and camelCase
     *  variants, and an exact-lowercase match silently drops the reference. */
    fun designFormKeys(el: El): List<Pair<String, String>> {
        val ext = extEl(el) ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (c in ext.children) {
            val rel = DESIGN_FORM_TAGS[c.tag.lowercase()] ?: continue
            val v = c.text?.trim()
            if (!v.isNullOrEmpty()) out.add(rel to v)
        }
        return out
    }

    /** Literal form keys pushed into a child scope via an in/out mapping onto `formKey`. */
    fun inoutFormKeys(mappings: List<Map<String, Any?>>?): List<Any?> =
        (mappings ?: emptyList()).filter {
            it["source"] != null && (it["target"] as? String)?.lowercase()?.contains("formkey") == true
        }.map { it["source"] }

    /**
     * The `<extensionElements>` children that carry a variable mapping, and the direction each implies.
     *
     * Flowable spells "pass this value in / take that value out" a different way for almost every task
     * flavour — `<flowable:in>` on a call activity, `<flowable:inputParameter>` on a service-registry or
     * agent task, `<flowable:eventInParameter>` on a send/receive-event task, `<flowable:variableMapping>`
     * on an Init-Variables task — but they all describe the same thing. [readIoParams] normalises every
     * flavour onto one `source -> target` record so a single call per element covers them all.
     */
    private val IO_PARAM_TAGS: Map<String, String> = linkedMapOf(
        "in" to "in",
        "out" to "out",
        "inputParameter" to "in",
        "outputParameter" to "out",
        "errorOutputParameter" to "error-out",
        "eventInParameter" to "in",
        "eventOutParameter" to "out",
        "variableMapping" to "in",
        "outputVariableName" to "out",
    )

    /**
     * Every in/out parameter mapping declared on `el`, normalised to `{dir, kind, source, target}`.
     *
     * `source` is always where the value comes from and `target` where it lands, so a reader never has to
     * know which flavour produced the record:
     *
     *  - `<flowable:in source="a" target="b"/>` → `in  a -> b`; a `sourceExpression` lands in `source`
     *    with `expression=true` (the distinction matters — an expression is not a variable name).
     *  - `<flowable:inputParameter name="p" value="${v}"/>` → `in  ${v} -> p` (the caller's value flows
     *    into the callee's parameter `p`).
     *  - `<flowable:outputParameter name="p" value="v"/>` → `out p -> v` (the callee's `p` flows into the
     *    caller's variable `v`); `errorOutputParameter` is the same shape on the error path.
     *  - `<flowable:variableMapping name="v" value="x"/>` → `in  x -> v`, `valueType` in `type`.
     *  - `<flowable:outputVariableName>c</…>` → `out null -> c` (the callee's result has no name).
     *
     * Optional keys (`type`, `transient`, `expression`) are only present when the model declares them, so
     * a mapping without them keeps the minimal three-key shape.
     */
    fun readIoParams(el: El): List<Map<String, Any?>> {
        val ext = extEl(el) ?: return emptyList()
        val out = ArrayList<Map<String, Any?>>()
        for (c in ext.children) {
            val dir = IO_PARAM_TAGS[c.tag] ?: continue
            var source: String? = null
            var target: String? = null
            var type: String? = null
            var expression = false
            when (c.tag) {
                "in", "out" -> {
                    val srcExpr = c.attr("sourceExpression")
                    source = c.attr("source") ?: srcExpr
                    expression = c.attr("source") == null && srcExpr != null
                    target = c.attr("target") ?: c.attr("targetExpression")
                }
                "inputParameter" -> {
                    source = c.attr("value")
                    target = c.attr("name")
                    type = c.attr("type")
                }
                "outputParameter", "errorOutputParameter" -> {
                    source = c.attr("name")
                    target = c.attr("value")
                    type = c.attr("type")
                }
                "eventInParameter", "eventOutParameter" -> {
                    source = c.attr("source") ?: c.attr("sourceExpression")
                    target = c.attr("target")
                }
                "variableMapping" -> {
                    val valExpr = c.attr("valueExpression")
                    source = c.attr("value") ?: valExpr
                    expression = c.attr("value") == null && valExpr != null
                    target = c.attr("name")
                    type = c.attr("valueType")
                }
                "outputVariableName" -> target = c.text?.trim()?.ifEmpty { null }
            }
            if (source == null && target == null) continue
            val rec = linkedMapOf<String, Any?>("dir" to dir, "kind" to c.tag, "source" to source, "target" to target)
            if (!type.isNullOrEmpty()) rec["type"] = type
            if (c.attr("transient") == "true") rec["transient"] = true
            // an expression is not a variable name — flag it so consumers can skip variable resolution
            if (expression || looksLikeExpression(source)) rec["expression"] = true
            out.add(rec)
        }
        return out
    }

    /**
     * The model this element calls, as `(kind, key)` — what its in/out parameters are actually mapped onto.
     *
     * A parameter list without its callee is half a story: "3 params on Lookup" says nothing about *which*
     * service the values go to. Stamped onto every record by the parsers so both the element's own detail
     * view and the callee's "Called with" view can name (and link) the other side.
     */
    fun calleeOf(el: El): Pair<String, String>? {
        val ext = extEl(el)
        if (ext != null) {
            ext.findChild("serviceMapping")?.attr("serviceModelKey").nonEmpty()?.let { return "service" to it }
            ext.findChild("agentMapping")?.attr("agentModelKey").nonEmpty()?.let { return "agent" to it }
            ext.findChild("dataObjectMapping")?.attr("definitionKey").nonEmpty()?.let { return "dataObject" to it }
        }
        when (el.tag) {
            "callActivity" -> el.attr("calledElement").nonEmpty()?.let { return "process" to it }
            "processTask" ->
                (el.textOfDescendant("processRefExpression") ?: el.attr("processRef")).nonEmpty()
                    ?.let { return "process" to it }
            "caseTask" ->
                (el.textOfDescendant("caseRefExpression") ?: el.attr("caseRef")).nonEmpty()
                    ?.let { return "case" to it }
        }
        // a BPMN "case" service task starts a case by definition key (attribute, or an older field injection)
        if (el.attr("type") == "case") {
            (el.attr("caseDefinitionKey") ?: readFields(el)["caseDefinitionKey"] as? String).nonEmpty()
                ?.let { return "case" to it }
        }
        // send/receive-event tasks and event-registry events map their payload onto an event model
        ext?.childText("eventType").nonEmpty()?.let { return "event" to it }
        // an HTTP task's callee is a URL rather than a model — still worth naming, just not linkable
        if (el.attr("type") == "http") {
            (readFields(el)["requestUrl"] as? String).nonEmpty()?.let { return "rest" to it }
        }
        return null
    }

    private fun String?.nonEmpty(): String? = this?.trim()?.ifEmpty { null }

    /** An attribute-derived out-parameter, for the `resultVariable`-style attributes that have no element. */
    fun resultVariableParam(kind: String, name: String?): Map<String, Any?>? =
        if (name.isNullOrEmpty()) null
        else linkedMapOf("dir" to "out", "kind" to kind, "source" to null, "target" to name)

    private fun looksLikeExpression(v: String?): Boolean =
        v != null && (v.contains("\${") || v.contains("#{") || v.contains("{{"))

    /** Field-injection values on a delegate/listener element (`<flowable:field>`). */
    fun readFields(el: El): LinkedHashMap<String, Any?> {
        val fields = LinkedHashMap<String, Any?>()
        val ext = extEl(el) ?: return fields
        for (fld in ext.findChildren("field")) {
            val name = fld.attr("name") ?: continue
            val s = fld.findChild("string")
            val e = fld.findChild("expression")
            fields[name] = when {
                s?.text?.trim()?.isNotEmpty() == true -> s.text!!.trim()
                e?.text?.trim()?.isNotEmpty() == true -> e.text!!.trim()
                else -> fld.attr("stringValue") ?: fld.attr("expression")
            }
        }
        return fields
    }

    /**
     * The Design "which model does this task call" extension elements: service registry, data object,
     * agent and template references.
     *
     * Shared by BPMN and CMMN — the elements are byte-identical in both dialects, only the `fromType`
     * label on the recorded reference differs. Returns the resolved keys so the caller can store them on
     * the task, which is what gives an in/out parameter list its callee.
     */
    fun readTaskMappings(ctx: Ctx, frm: Any?, ftype: String, ffile: String, el: El): LinkedHashMap<String, Any?> {
        val info = LinkedHashMap<String, Any?>()
        val ext = extEl(el) ?: return info
        ext.findChild("serviceMapping")?.let { sm ->
            info["serviceModelKey"] = sm.attr("serviceModelKey")
            info["operationKey"] = sm.attr("operationKey")
            ctx.addRef(frm, ftype, ffile, "serviceMapping", "service", sm.attr("serviceModelKey"))
            ctx.addOpUse(frm, "service", sm.attr("serviceModelKey"), sm.attr("operationKey"))
        }
        ext.findChild("dataObjectMapping")?.let { dom ->
            info["dataObjectKey"] = dom.attr("definitionKey")
            dom.attr("operationKey")?.ifEmpty { null }?.let { info["dataObjectOperationKey"] = it }
            ctx.addRef(frm, ftype, ffile, "dataObjectMapping", "dataObject", dom.attr("definitionKey"))
        }
        ext.findChild("agentMapping")?.let { am ->
            info["agentModelKey"] = am.attr("agentModelKey")
            am.attr("operationKey")?.ifEmpty { null }?.let { info["agentOperationKey"] = it }
            ctx.addRef(frm, ftype, ffile, "agentMapping", "agent", am.attr("agentModelKey"))
        }
        return info
    }

    /** Design "static model key" extension elements (case-view / case-page / AI config) → (kind, rel).
     *  All of them are CDATA-text children of `extensionElements`, not attributes. */
    private val STATIC_KEY_TAGS: Map<String, Pair<String, String>> = linkedMapOf(
        "static-process-key" to ("process" to "starts-process"),
        "static-case-key" to ("case" to "starts-case"),
        "static-form-key" to ("form" to "static-form"),
        "static-manual-start-form-key" to ("form" to "manual-start-form"),
        "static-decision-table-key" to ("decision" to "static-decision"),
    )

    /** Group-permission extension elements → the access action they grant. */
    private val PERMISSION_GROUP_TAGS: Map<String, String> = linkedMapOf(
        "watcher-groups" to "watch",
        "participant-groups" to "participate",
        "participant-candidate-groups" to "participate",
        "event-listener-permission-groups" to "trigger",
        "manual-activation-permission-groups" to "manually-start",
    )

    /** Record the Design static-key references and group permissions declared under [el]. */
    fun collectDesignExtensionRefs(
        ctx: Ctx, frm: Any?, ftype: String, accessType: String, ffile: String, el: El,
    ) {
        val ext = extEl(el) ?: return
        for ((tag, kindRel) in STATIC_KEY_TAGS) {
            ctx.addRef(frm, ftype, ffile, kindRel.second, kindRel.first, ext.childText(tag))
        }
        val eid = el.attr("id") ?: ""
        for ((tag, action) in PERMISSION_GROUP_TAGS) {
            val g = ext.childText(tag)
            if (!g.isNullOrEmpty()) ctx.addAccess(frm, accessType, "element:$eid", action, g)
        }
    }

    /** Execution/task/plan-item listeners declared on an element. */
    fun readListeners(el: El): List<Map<String, Any?>> {
        val ext = extEl(el) ?: return emptyList()
        val out = ArrayList<Map<String, Any?>>()
        for (tag in listOf("executionListener", "taskListener", "planItemLifecycleListener")) {
            for (lst in ext.findChildren(tag)) {
                val entry = linkedMapOf<String, Any?>(
                    "kind" to tag,
                    "event" to (lst.attr("event") ?: lst.attr("targetState")),
                    "class" to lst.attr("class"),
                    "expression" to lst.attr("expression"),
                    "delegateExpression" to lst.attr("delegateExpression"),
                    "script" to lst.childText("script"),
                )
                // throw-event listeners publish a signal/message/error by name/code
                for (a in listOf("signalName", "messageName", "errorCode")) {
                    lst.attr(a)?.ifEmpty { null }?.let { entry[a] = it }
                }
                out.add(entry)
            }
        }
        return out
    }

    private val LISTENER_BEAN_RE = Regex("[#$]\\{\\s*([A-Za-z_]\\w*)")

    /** Record class/bean/script references declared by a set of listeners. [element]/[elementName] name
     *  the element the listeners hang off, so a variable a listener script touches can say where. */
    fun collectListenerRefs(
        ctx: Ctx, frm: Any?, ftype: String, ffile: String, listeners: List<Map<String, Any?>>,
        element: Any? = null, elementName: Any? = null,
    ) {
        for (ls in listeners) {
            val rel = "${ls["kind"]}:${ls["event"]}"
            (ls["class"])?.let { ctx.addRef(frm, ftype, ffile, rel, "class", it) }
            for (ex in listOf(ls["delegateExpression"], ls["expression"])) {
                val exStr = ex as? String ?: continue
                for (mm in LISTENER_BEAN_RE.findAll(exStr)) {
                    val b = mm.groupValues[1]
                    if (b !in Constants.FLOWABLE_CONTEXT) ctx.addRef(frm, ftype, ffile, rel, "bean", b)
                }
            }
            // throw-event listeners publish by name/code — same shared node as event throws
            (ls["signalName"])?.let { ctx.addRef(frm, ftype, ffile, "throws-signal", "signal", it) }
            (ls["messageName"])?.let { ctx.addRef(frm, ftype, ffile, "throws-message", "message", it) }
            (ls["errorCode"])?.let { ctx.addRef(frm, ftype, ffile, "throws-error", "error", it) }
            VarHarvest.collectScriptVars(ctx, ls["script"] as? String, listOf(frm),
                element = element, elementName = elementName, elementType = ls["kind"] as? String)
        }
    }

    /** The (kind, value) of the first event-definition child of a BPMN event element. */
    fun eventInfo(ev: El): Pair<String?, String?> {
        for (c in ev.children) {
            if (c.tag in BPMN_EVENT_DEFS) {
                val kind = c.tag.replace("EventDefinition", "")
                val value = c.childText("timeDuration") ?: c.childText("timeCycle")
                    ?: c.childText("timeDate") ?: c.attr("messageRef") ?: c.attr("signalRef")
                    ?: c.attr("errorRef") ?: c.attr("escalationRef")
                return kind to value
            }
        }
        return null to null
    }
}
