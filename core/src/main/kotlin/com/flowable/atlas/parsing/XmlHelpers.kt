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

    /** `<flowable:in>` / `<flowable:out>` variable mappings on an element. */
    fun readInOut(el: El): List<Map<String, Any?>> {
        val ext = extEl(el) ?: return emptyList()
        val out = ArrayList<Map<String, Any?>>()
        for (dir in listOf("in", "out")) {
            for (m in ext.findChildren(dir)) {
                out.add(linkedMapOf(
                    "dir" to dir,
                    "source" to (m.attr("source") ?: m.attr("sourceExpression")),
                    "target" to m.attr("target"),
                ))
            }
        }
        return out
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

    /** Record class/bean/script references declared by a set of listeners. */
    fun collectListenerRefs(ctx: Ctx, frm: Any?, ftype: String, ffile: String, listeners: List<Map<String, Any?>>) {
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
            VarHarvest.collectScriptVars(ctx, ls["script"] as? String, listOf(frm))
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
