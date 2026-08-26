package com.flowable.atlas.parsing

import com.flowable.atlas.graph.Ctx
import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.script.ScriptContext
import com.flowable.atlas.script.ScriptValidator

/**
 * The per-model-family parsers — a port of the `parse_*` functions in `flowable_atlas.py`. Each takes
 * the raw bytes, the shared [Ctx] (into which it records cross-model references), and the model file's
 * project-relative path; it returns the parsed model as an ordered map (or a list of them, for XML
 * containers that may declare several definitions).
 *
 * Ported incrementally (TDD, one family at a time, each verified against the golden). [PARSERS] maps a
 * model type to its parser; types without an entry are still discovered/indexed but not yet parsed.
 */
object ModelParsers {

    /** Model type → parser. Mirrors the Python `PARSERS` dict. bpmn/cmmn live in [BackendModelParsers]. */
    val PARSERS: Map<String, (ByteArray, Ctx, String) -> Any> = mapOf(
        "app" to ::parseApp,
        "bpmn" to BackendModelParsers::parseBpmn,
        "cmmn" to BackendModelParsers::parseCmmn,
        "dmn" to ::parseDmn,
        "form" to ::parseForm,
        "page" to ::parseForm,
        "agent" to ::parseAgent,
        "service" to ::parseService,
        "channel" to ::parseChannel,
        "event" to ::parseEvent,
        "dataDictionary" to ::parseDictionary,
        "dataObject" to ::parseDataObject,
        "securityPolicy" to ::parsePolicy,
        "action" to ::parseAction,
    )

    private val GENERIC_KEYS = listOf("key", "name", "description", "type", "subType", "modelType")

    /** Form component types that render/act but never bind a variable of their own. */
    private val NON_BINDING_FIELD_TYPES = setOf(
        "text", "headline", "horizontalLine", "image", "html", "link", "spacer", "expression",
        "container", "panel", "tabs", "tab", "workAction", "outcomeButton",
    )

    /** Field-id roots that are frontend scratch space, never process/case variables. */
    private val FIELD_ID_IGNORE = setOf("temp", "response", "payload", "root", "item", "self")

    /** Buttons whose `value` binding is where the call's result is stored, not a caption or a placeholder. */
    private val RESULT_BINDING_TYPES = setOf("scriptButton", "restButton")

    // A data-source / link / navigation URL can invoke a service operation with the target and operation
    // keys as *literal* query params even though the host is a dynamic `{{endpoints.*}}` placeholder — e.g.
    // `{{endpoints.dataobject}}/dataobject-runtime/data-object-instances?dataObjectDefinitionKey=<key>&dataObjectOperationKey=<op>&…`.
    // The structured `extraSettings.dataObjectDefinitionKey` / `serviceModel` paths miss these, so URL
    // strings are scanned for them too. Only literal keys survive [Ctx.addOpUse]'s dynamic-value guard.
    private val DO_DEF_RE = Regex("dataObjectDefinitionKey=([^&\\s\"']+)")
    private val DO_OP_RE = Regex("dataObjectOperationKey=([^&\\s\"']+)")
    private val SVC_KEY_RE = Regex("serviceModelKey=([^&\\s\"']+)")
    private val SVC_OP_RE = Regex("(?<![A-Za-z])operationKey=([^&\\s\"']+)")

    /** Extract data-object / service operation usages embedded as query params in a data-source or
     *  navigation [url] and record them (ref + op-use) against [key]. No-op unless a `…OperationKey=` /
     *  `serviceModelKey=` param is present, so scanning arbitrary URLs stays cheap and side-effect free. */
    private fun recordUrlOpUses(url: String?, key: Any?, mtype: String, ffile: String, ctx: Ctx) {
        if (url == null) return
        if (url.contains("dataObjectOperationKey=")) {
            val doKey = DO_DEF_RE.find(url)?.groupValues?.get(1)
            val doOp = DO_OP_RE.find(url)?.groupValues?.get(1)
            if (doKey != null && doOp != null) {
                ctx.addRef(key, mtype, ffile, "field-dataObject", "dataObject", doKey)
                ctx.addOpUse(key, "dataObject", doKey, doOp)
            }
        }
        if (url.contains("serviceModelKey=")) {
            val svcKey = SVC_KEY_RE.find(url)?.groupValues?.get(1)
            val svcOp = SVC_OP_RE.find(url)?.groupValues?.get(1)
            if (svcKey != null && svcOp != null) {
                ctx.addRef(key, mtype, ffile, "field-service", "service", svcKey)
                ctx.addOpUse(key, "service", svcKey, svcOp)
            }
        }
    }

    /** Recursively visit every JSON object in a tree (ElementTree-free `_walk_json`). */
    private fun walkJson(node: Any?, fn: (Map<String, Any?>) -> Unit) {
        when (node) {
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST") fn(node as Map<String, Any?>)
                node.values.forEach { walkJson(it, fn) }
            }
            is List<*> -> node.forEach { walkJson(it, fn) }
        }
    }

    /** Parse raw model bytes as a JSON object (Flowable's non-XML models are JSON). */
    @Suppress("UNCHECKED_CAST")
    private fun json(data: ByteArray): Map<String, Any?> =
        MiniJson.parse(String(data, Charsets.UTF_8)) as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun objOf(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    private fun listOfObjs(v: Any?): List<Map<String, Any?>> =
        (v as? List<*>).orEmpty().mapNotNull { objOf(it) }

    /** How many decision-table rows are kept per decision; beyond that `rulesTruncated` records the real count. */
    private const val DMN_RULE_LIMIT = 500

    /** `.dmn` — one entry per `<decision>`, with its decision-table shape when present. */
    fun parseDmn(data: ByteArray, ctx: Ctx, ffile: String): List<Map<String, Any?>> {
        val root = AtlasXml.parse(data)
        val out = ArrayList<Map<String, Any?>>()
        for (dec in root.iter("decision")) {
            val key = dec.attr("id")
            val info = linkedMapOf<String, Any?>(
                "key" to key, "name" to dec.attr("name"), "file" to ffile,
            )
            val t = dec.findDescendant("decisionTable")
            if (t != null) {
                info["hitPolicy"] = t.attr("hitPolicy") ?: "UNIQUE"
                // the input variable lives in inputExpression/<text>; `label` is only a display name
                info["inputs"] = t.findChildren("input").map {
                    it.attr("label") ?: it.textOfDescendant("text") ?: it.textOfDescendant("inputExpression")
                }
                info["outputs"] = t.findChildren("output").map { it.attr("label") ?: it.attr("name") }
                info["ruleCount"] = t.findChildren("rule").size
                // The rows themselves — the actual business logic of a decision. Only the counts were
                // kept before, so a value or condition that lives in a cell (`> 100`, `"REJECTED"`) was
                // invisible: neither shown nor findable. Wide tables are common, so cap the list and
                // say so rather than embedding thousands of rows in the explorer payload.
                val ruleEls = t.findChildren("rule")
                info["rules"] = ruleEls.take(DMN_RULE_LIMIT).map { r ->
                    linkedMapOf(
                        "id" to r.attr("id"),
                        "inputs" to r.findChildren("inputEntry").map { it.textOfDescendant("text") },
                        "outputs" to r.findChildren("outputEntry").map { it.textOfDescendant("text") },
                        "annotation" to r.textOfDescendant("description"),
                    )
                }
                if (ruleEls.size > DMN_RULE_LIMIT) info["rulesTruncated"] = ruleEls.size
                // A labelled input hides the expression it evaluates (`inputs` prefers the label), and
                // that expression is what a reader looks for when tracing a variable into a decision.
                val inputExprs = t.findChildren("input").mapNotNull { it.textOfDescendant("text") }
                if (inputExprs.isNotEmpty()) info["inputExpressions"] = inputExprs
                // A decision table reads its input variables from, and writes its output names back
                // into, the calling scope — index both so decisions join the variable graph.
                for (inp in t.findChildren("input")) {
                    val v = inp.textOfDescendant("text")?.trim()?.substringBefore('.')
                    ctx.addVar(key, v)
                    ctx.addVarSite(key, v, Ctx.READ, "dmnInput", inp.attr("id"), inp.attr("label"), "dmnInput")
                }
                for (outp in t.findChildren("output")) {
                    ctx.addVar(key, outp.attr("name"))
                    ctx.addVarSite(key, outp.attr("name"), Ctx.WRITE, "dmnOutput",
                        outp.attr("id"), outp.attr("label"), "dmnOutput")
                }
            }
            // DRD: a decision may require other decisions (informationRequirement/requiredDecision)
            for (req in dec.findChildren("informationRequirement")) {
                val href = req.findChild("requiredDecision")?.attr("href")
                ctx.addRef(key, "dmn", ffile, "requires", "decision", href?.removePrefix("#"))
            }
            out.add(info)
        }
        // Decision services are resolvable targets too — a serviceTask type=dmn may reference one
        // via decisionServiceReferenceKey, exactly like a plain decision key.
        for (ds in root.iter("decisionService")) {
            val dsKey = ds.attr("id")
            val members = (ds.findChildren("outputDecision") + ds.findChildren("encapsulatedDecision"))
                .mapNotNull { it.attr("href")?.removePrefix("#") }
            for (m in members) ctx.addRef(dsKey, "dmn", ffile, "contains-decision", "decision", m)
            out.add(linkedMapOf(
                "key" to dsKey, "name" to ds.attr("name"), "file" to ffile,
                "decisionService" to true, "decisions" to members,
            ))
        }
        return out
    }

    /** `.event` — the event key/name plus its payload names and correlation-parameter names. */
    @Suppress("UNCHECKED_CAST")
    fun parseEvent(data: ByteArray, ctx: Ctx, ffile: String): Map<String, Any?> {
        val doc = MiniJson.parse(String(data, Charsets.UTF_8)) as Map<String, Any?>
        val payload = (doc["payload"] as? List<Map<String, Any?>>) ?: emptyList()
        val correlation = ArrayList<Any?>()
        (doc["correlationParameters"] as? List<Map<String, Any?>>)?.forEach { correlation.add(it["name"]) }
        for (p in payload) {
            if (p["correlationParameter"] == true && p["name"] !in correlation) correlation.add(p["name"])
        }
        // which channels carry this event, and the data-dictionary types its payload fields use
        for ((chKey, rel) in listOf("inboundChannelKeys" to "inbound-channel", "outboundChannelKeys" to "outbound-channel")) {
            for (ck in (doc[chKey] as? List<*> ?: emptyList<Any?>())) {
                ctx.addRef(doc["key"], "event", ffile, rel, "channel", ck)
            }
        }
        for (p in payload) {
            ctx.addRef(doc["key"], "event", ffile, "typed-by-dictionary", "dataDictionary",
                objOf(p["extensionProperties"])?.get("dataDictionaryModelKey"))
        }
        return linkedMapOf(
            "key" to doc["key"], "name" to doc["name"], "file" to ffile,
            "correlation" to correlation,
            "payload" to payload.map { it["name"] },
        )
    }

    /** `.service` — connector metadata, column mappings and operations; records REST calls in [Ctx]. */
    fun parseService(data: ByteArray, ctx: Ctx, ffile: String): Map<String, Any?> {
        val doc = json(data)
        val cfg = objOf(doc["config"]) ?: emptyMap()
        val base = (cfg["baseUrl"] ?: cfg["url"]) as? String
        val columns = listOfObjs(doc["columnMappings"]).map {
            linkedMapOf("name" to (it["name"] ?: it["columnName"]), "columnName" to it["columnName"], "type" to it["type"])
        }
        val operations = ArrayList<Any?>()
        val info = linkedMapOf<String, Any?>(
            "key" to doc["key"], "name" to doc["name"], "file" to ffile,
            "type" to doc["type"], "baseUrl" to base, "auth" to objOf(cfg["authentication"])?.get("type"),
            "tableName" to doc["tableName"], "referencedLiquibaseModelKey" to doc["referencedLiquibaseModelKey"],
            "referenceKey" to doc["referenceKey"], "columns" to columns, "operations" to operations,
        )
        // Cross-model references (parity with the platform's ServiceModelReferenceExtractor):
        // referenceKey → data object; typeReference.modelKey → data dictionary (output and
        // per-operation input/output parameters); operation body templates → template model;
        // a column relation joins to the table of another service model.
        ctx.addRef(doc["key"], "service", ffile, "service-dataObject", "dataObject", doc["referenceKey"])
        for (cm in listOfObjs(doc["columnMappings"])) {
            ctx.addRef(doc["key"], "service", ffile, "relates-to-service", "service",
                objOf(cm["relation"])?.get("referenceServiceDefinitionKey"))
        }
        fun dictionaryRefs(params: Any?) {
            for (p in listOfObjs(params)) {
                ctx.addRef(doc["key"], "service", ffile, "typed-by-dictionary", "dataDictionary",
                    objOf(p["typeReference"])?.get("modelKey"))
            }
        }
        dictionaryRefs(doc["outputParameters"])
        for (op in listOfObjs(doc["operations"])) {
            val oc = objOf(op["config"]) ?: emptyMap()
            val rawUrl = oc["url"] as? String
            var full = rawUrl
            if (base != null && full != null && !full.startsWith("http")) {
                full = base.trimEnd('/') + "/" + full.trimStart('/')
            }
            operations.add(linkedMapOf(
                "key" to op["key"], "name" to op["name"], "method" to oc["method"],
                "url" to oc["url"], "fullUrl" to full,
                "params" to operationParams(op["inputParameters"]),
                // An operation's declared outputs are the other half of its contract — what the caller
                // gets back and can map into a variable. Falls back to the service-level list, which
                // Flowable shares across operations that declare none of their own.
                "outParams" to operationParams(op["outputParameters"] ?: doc["outputParameters"]),
            ))
            dictionaryRefs(op["inputParameters"])
            dictionaryRefs(op["outputParameters"])
            ctx.addRef(doc["key"], "service", ffile, "body-template", "template",
                objOf(oc["bodyTemplateModel"])?.get("bodyTemplateTemplateModelKey"))
            // Only record a rest call when we actually have a URL — a method-only operation has no
            // graph edge to draw, and a null `url` would violate the invariant every other restCalls
            // producer upholds (and break GraphBuilder's non-null cast).
            if (full != null) {
                ctx.restCalls.add(linkedMapOf(
                    "source" to doc["key"], "sourceFile" to ffile, "where" to op["key"],
                    "method" to (oc["method"] ?: "?"), "url" to full, "kind" to "service-op",
                ))
            }
        }
        return info
    }

    /** Parameters a service operation declares (its contract towards a caller), name + type. */
    private fun operationParams(params: Any?): List<Map<String, Any?>> =
        listOfObjs(params).filter { it["name"] != null }
            .map { linkedMapOf("name" to it["name"], "type" to it["type"]) }

    /** `.policy` — a security policy's permission → roles mapping (dict or list shape). */
    fun parsePolicy(data: ByteArray, ctx: Ctx, ffile: String): Map<String, Any?> {
        val doc = json(data)
        val perms = ArrayList<Map<String, Any?>>()
        val pmRaw = doc["permissionMappings"]
        // dict shape: {permKey: definition}; list shape: [{key, definition|self}]
        val items: List<Pair<Any?, Map<String, Any?>?>> = when (pmRaw) {
            is Map<*, *> -> pmRaw.entries.map { it.key to objOf(it.value) }
            is List<*> -> pmRaw.mapNotNull { objOf(it) }.map { it["key"] to (objOf(it["definition"]) ?: it) }
            else -> emptyList()
        }
        for ((pk, pv) in items) {
            if (pv == null) continue
            val roles = (objOf(pv["permissionValues"]) ?: emptyMap()).entries
                .filter { truthy(it.value) }.map { it.key }
            perms.add(linkedMapOf("key" to pk, "label" to pv["label"], "roles" to roles))
            ctx.groups.addAll(roles)
            // group → policy edges: which roles hold this permission (action = permission key)
            ctx.addAccess(doc["key"], "securityPolicy", "policy", pk?.toString() ?: "permission",
                roles.joinToString(","))
        }
        return linkedMapOf(
            "key" to doc["key"], "name" to doc["name"], "file" to ffile,
            "type" to doc["type"], "permissions" to perms,
        )
    }

    /** `.app` — the app's metadata, variables, pages and child-model list; records access + contains. */
    fun parseApp(data: ByteArray, ctx: Ctx, ffile: String): Map<String, Any?> {
        val doc = json(data)
        val design = objOf(objOf(doc["extension"])?.get("design")) ?: emptyMap()
        val variables = objOf(doc["variables"]) ?: emptyMap()
        val key = doc["key"]
        val childModels = (design["childModels"] as? List<*>) ?: emptyList<Any?>()
        val info = linkedMapOf<String, Any?>(
            "key" to key, "name" to doc["name"], "file" to ffile,
            "description" to doc["description"], "theme" to doc["theme"],
            "paletteDefinitionCategory" to doc["paletteDefinitionCategory"],
            "usersAccess" to doc["usersAccess"], "groupsAccess" to doc["groupsAccess"],
            "variables" to variables.entries.map { linkedMapOf("key" to it.key, "type" to objOf(it.value)?.get("type")) },
            "pages" to listOfObjs(doc["pageModels"]).map { linkedMapOf("key" to it["key"], "access" to it["accessPermissions"]) },
            "childModels" to childModels,
        )
        for (cm in childModels.mapNotNull { objOf(it) }) {
            val modelKind = if (truthy(cm["type"])) cm["type"].toString() else "?"
            ctx.addRef(key, "app", ffile, "contains", "model:$modelKind", cm["key"])
        }
        ctx.addAccess(key, "app", "app", "open-app", doc["groupsAccess"], doc["usersAccess"])
        for (p in listOfObjs(doc["pageModels"])) {
            ctx.addAccess(p["key"] ?: key, "page", "page", "view", p["accessPermissions"])
            // pages listed only under pageModels (not in extension.design.childModels) still
            // belong to the app — dedupe/edge-dedupe absorbs the overlap when both are present
            ctx.addRef(key, "app", ffile, "contains", "model:page", p["key"])
        }
        return info
    }

    /** `.form` / `.page` — fields, outcomes, subforms, data sources; records refs + REST calls. */
    fun parseForm(data: ByteArray, ctx: Ctx, ffile: String): Map<String, Any?> {
        val doc = json(data)
        val meta = objOf(doc["metadata"]) ?: emptyMap()
        val key = meta["key"]
        val defaultType = if (ffile.lowercase().endsWith(".page")) "page" else "form"
        val mtype = (meta["modelType"] as? String) ?: defaultType
        val fields = ArrayList<Any?>()
        val outcomes = ArrayList<Any?>()
        val dataSources = ArrayList<Any?>()
        val subforms = ArrayList<Any?>()
        val ioParameters = ArrayList<Map<String, Any?>>()
        val restCalls = ArrayList<Map<String, Any?>>()
        val info = linkedMapOf<String, Any?>(
            "key" to key, "name" to meta["name"], "file" to ffile, "modelType" to mtype,
            "fields" to fields, "outcomes" to outcomes, "dataSources" to dataSources, "subforms" to subforms,
            "ioParameters" to ioParameters, "restCalls" to restCalls,
        )
        for (oc in listOfObjs(doc["outcomes"])) {
            outcomes.add(linkedMapOf("value" to oc["value"], "label" to oc["label"]))
            ctx.addRef(key, mtype, ffile, "outcome-form", "form", oc["outcomeFormKey"])
        }
        // the variable the chosen outcome is stored in (the key is all-lowercase in the model JSON)
        ctx.addVar(key, doc["outcomevariablename"])
        ctx.addVarSite(key, doc["outcomevariablename"], Ctx.WRITE, "formOutcome", elementType = "form")
        // Which button the user pressed is what the Work UI shows in a task list and what a query filters
        // on, so an outcome variable no flow condition reads is not evidence of anything. Recording the
        // write but not claiming to know its readers is the honest half.
        ctx.markReadsUnknown(doc["outcomevariablename"])
        fun visit(n: Map<String, Any?>) {
            // The component's own record, kept mutable: what a *button* does lives in `extraSettings`,
            // which this walk only reaches further down.
            var component: MutableMap<String, Any?>? = null
            if (ModelJsonReader.isFormComponent(n)) {
                val ftype = (n["type"] as? String) ?: ""
                val isButton = ftype in ModelJsonReader.BUTTON_TYPES
                // A button has no `label`; its caption is `extraSettings.text` (see [isFormComponent]),
                // failing that a localised override, failing that — on an icon-only REST or link button —
                // its literal `value`. A `{{…}}` value is a binding, not a caption: on an expression
                // button it is where the *result* lands.
                val label = pyOr(
                    pyOr(pyOr(n["label"], objOf(n["extraSettings"])?.get("text")), i18nCaption(n)),
                    if (isButton) (n["value"] as? String)?.takeIf { !it.contains("{{") } else null,
                )
                component = linkedMapOf(
                    "id" to n["id"], "type" to n["type"], "label" to label,
                    "required" to (n["isRequired"] ?: false), "value" to n["value"],
                )
                // What the modeller wrote about this component for whoever reads the form next — the
                // form equivalent of a BPMN element's documentation, and just as much the answer to "why
                // is this here" ("Disabled for privileged users, because …").
                if (truthy(n["description"])) component["description"] = n["description"]
                // Whether it renders at all, whether it can be pressed, whether its value is submitted —
                // recorded for every component, not just buttons: a hidden input matters as much as a
                // hidden button, and 252 of 338 buttons in one real project are `visible: false` (the
                // auto-executing worker pattern), which a reader has no way to guess.
                gatingOf(n)?.let { component["settings"] = it }
                fields.add(component)
                if (n["type"] == "outcomeButton" && truthy(n["value"])) {
                    outcomes.add(linkedMapOf("value" to n["value"], "label" to label))
                }
                // A data-entry field's id is the variable path the form reads/writes at runtime — index
                // its root so the form joins the variable graph. Buttons don't bind a variable, and a
                // display component whose value is a {{…}} binding reads through the expression pass.
                val bound = (n["value"] as? String)?.contains("{{") == true
                if (!bound && !NON_BINDING_FIELD_TYPES.contains(ftype) && !ftype.lowercase().contains("button")) {
                    val root = n["id"].toString().trimStart('$').substringBefore('.').substringBefore('[')
                    if (root !in FIELD_ID_IGNORE) {
                        ctx.addVar(key, root, "form_field_use")
                        // A Work form field is prefilled *from* the variable and writes it back on
                        // submit, and nothing in the model says which of the two a given field is for.
                        // Recording both is the honest answer — and it means a field alone never makes a
                        // variable look unread.
                        for (d in listOf(Ctx.READ, Ctx.WRITE)) {
                            ctx.addVarSite(key, root, d, "formField", n["id"], label, ftype)
                        }
                    }
                }
                // An expression button computes a value, a REST button takes one out of its response, and
                // both hand it to their own `value` binding — that is where the result is stored, and it
                // is measurably always a binding on those two (178 of 178 and 87 of 87 in one real
                // project), while a `workAction` carries a placeholder `"."` there and stores nothing.
                // Buttons are excluded from the binding pass above because they bind no input, so without
                // this the target looked neither read nor written. The read side needs nothing: whoever
                // renders `{{x}}` is picked up already.
                if (ftype in RESULT_BINDING_TYPES) {
                    val target = (n["value"] as? String)?.takeIf { it.contains("{{") }
                    if (target != null) component["stores"] = target
                    bindingRoot(target)?.let { root ->
                        ctx.addVar(key, root, "form_field_use")
                        ctx.addVarSite(key, root, Ctx.WRITE, ftype, n["id"], label, ftype)
                    }
                }
            }
            // an outcome button can open a follow-up form directly on the field definition
            if (truthy(n["outcomeFormKey"])) ctx.addRef(key, mtype, ffile, "outcome-form", "form", n["outcomeFormKey"])
            val es = objOf(n["extraSettings"])
            if (es != null) {
                if (truthy(es["formRef"])) {
                    subforms.add(es["formRef"]); ctx.addRef(key, mtype, ffile, "subform", "form", es["formRef"])
                }
                if (truthy(es["dataObjectDefinitionKey"])) {
                    dataSources.add(linkedMapOf("kind" to "dataObject", "key" to es["dataObjectDefinitionKey"], "op" to es["dataObjectOperationKey"]))
                    ctx.addRef(key, mtype, ffile, "field-dataObject", "dataObject", es["dataObjectDefinitionKey"])
                    ctx.addOpUse(key, "dataObject", es["dataObjectDefinitionKey"], es["dataObjectOperationKey"])
                }
                // A select/table reads its options over REST: `queryUrl` for the list, `lookupUrl` to
                // resolve a stored id back to a label. Both are plain GETs.
                for (uk in listOf("queryUrl", "lookupUrl")) {
                    if (!truthy(es[uk])) continue
                    dataSources.add(linkedMapOf("kind" to "rest", "url" to es[uk]))
                    ctx.restCalls.add(linkedMapOf("source" to key, "sourceFile" to ffile, "where" to n["id"], "method" to "GET", "url" to es[uk], "kind" to "form-query"))
                }
                val sm = objOf(es["serviceModel"])
                if (sm != null && truthy(sm["serviceModelKey"])) {
                    dataSources.add(linkedMapOf("kind" to "service", "key" to sm["serviceModelKey"], "op" to sm["operationKey"]))
                    ctx.addRef(key, mtype, ffile, "field-service", "service", sm["serviceModelKey"])
                    ctx.addOpUse(key, "service", sm["serviceModelKey"], sm["operationKey"])
                }
                for (fk in listOf("dataObjectDataTableCreateFormKey", "dataObjectDataTableEditFormKey", "dataObjectDataTableViewFormKey")) {
                    if (truthy(es[fk])) ctx.addRef(key, mtype, ffile, fk, "form", es[fk])
                }
                if (truthy(es["expandablePanel"])) ctx.addRef(key, mtype, ffile, "datatable-detail-form", "form", es["expandablePanel"])
                // Like the process/case references below, the newer Design editor writes the action
                // reference as `{key, id}` rather than a bare key.
                if (truthy(es["actionDefinitionKey"])) {
                    ctx.addRef(key, mtype, ffile, "triggers-action", "action", modelRefKey(es["actionDefinitionKey"]))
                }
                // An agent button references an agent model the same way a service button references a
                // service — this was the one model reference on a form that went unrecorded.
                val am = objOf(es["agentModel"])
                if (am != null && truthy(am["agentModelKey"])) {
                    ctx.addRef(key, mtype, ffile, "field-agent", "agent", am["agentModelKey"])
                }
                // A create-instance button starts a process/case; a query data source runs a query
                // model. The reference is a bare key or a {key: …} object, depending on the version.
                ctx.addRef(key, mtype, ffile, "starts-process", "process", modelRefKey(es["processReference"]))
                ctx.addRef(key, mtype, ffile, "starts-case", "case", modelRefKey(es["caseReference"]))
                ctx.addRef(key, mtype, ffile, "runs-query", "query", modelRefKey(es["query"]))
                // A button/section can be gated to groups: ["group1"] or [{"permission-group": "group1"}].
                val pgs = (es["permissionGroups"] as? List<*> ?: emptyList<Any?>())
                    .map { if (it is Map<*, *>) it["permission-group"] else it }
                    .filterIsInstance<String>().filter { it.isNotEmpty() }
                if (pgs.isNotEmpty()) {
                    ctx.addAccess(key, mtype, "field:${n["id"]}", "use", pgs.joinToString(","))
                }
                // The payload a button sends to (and maps back from) whatever it invokes. Each record
                // carries the callee, which is what lets the invoked action/agent/service turn around and
                // show the values its callers actually pass.
                val (refKind, refKey) = calleeOf(es, n)
                val payload = payloadParams(es, refKind, refKey)
                if (payload.isNotEmpty()) {
                    ctx.addParams(
                        ioParameters, key, n["id"], pyOr(n["label"], es["text"]),
                        (n["type"] as? String) ?: "button", payload, refKind,
                    )
                }
                // What this component *does*, on the component's own record — the model it calls, the
                // settings that decide what pressing it sends, and which payload side is in force. The
                // form's reference list said an action was triggered; only this says by which button,
                // with what, and under which condition.
                component?.let { c ->
                    if (refKind != null && truthy(refKey)) {
                        c["callee"] = linkedMapOf<String, Any?>("kind" to refKind, "key" to refKey)
                    }
                    buttonSettings(n, es)?.let { flavour ->
                        @Suppress("UNCHECKED_CAST")
                        val gates = c["settings"] as? MutableMap<String, Any?>
                        if (gates == null) c["settings"] = flavour else gates.putAll(flavour)
                    }
                    payloadModes(es)?.let { c["payloadMode"] = it }
                }
                // Data-source / lookup / navigation URLs (queryUrl, lookupUrl, navigationUrl, …) can embed a
                // dataObject/service operation as literal query params — pick those up as op-uses.
                for (v in es.values) if (v is String) recordUrlOpUses(v, key, mtype, ffile, ctx)
            }
            // A REST/link button's endpoint. Real Design keeps it on `extraSettings.url` (palette
            // `rest-button-url`); only hand-written and legacy models put it on the component itself.
            // Reading `extraSettings` here — rather than wherever the walk happens to land — is what
            // gives the call a `where`: on the bare `extraSettings` map there is no id to attribute it to.
            // `extraSettings.method` is omitted whenever it is the palette default, hence the fallback.
            val url = (pyOr(es?.get("url"), n["url"]) as? String)?.trim()
            if (truthy(n["id"]) && !url.isNullOrEmpty()) {
                val method = ((es?.get("method") as? String)?.takeIf { it.isNotBlank() } ?: "get").uppercase()
                restCalls.add(linkedMapOf(
                    "where" to n["id"], "method" to method, "url" to url, "path" to es?.get("path"),
                ))
                ctx.restCalls.add(linkedMapOf("source" to key, "sourceFile" to ffile, "where" to n["id"], "method" to method, "url" to url, "kind" to "form-button"))
                recordUrlOpUses(url, key, mtype, ffile, ctx)
            }
            // Link components carry their target URL in `value`.
            (n["value"] as? String)?.let { recordUrlOpUses(it, key, mtype, ffile, ctx) }
        }
        // The whole document, not just `rows`: a page keeps header/toolbar buttons outside the row grid,
        // and an outcome's `navigationUrl` lives on the outcome. The component predicate gates what
        // actually becomes a field, so widening the walk only adds what was previously unreachable.
        walkJson(doc, ::visit)
        return info
    }

    /**
     * The payload-mapping `extraSettings` keys a form/page button can carry, and the direction each means.
     *
     * These are the Design dialog's "Send payload map" and "Store response attributes". The same
     * `extraSettings` path is used by every button flavour — Action, REST, Service, Agent, Create-Instance,
     * Data-Object table — so one reader covers them all.
     */
    private val PAYLOAD_MAPPINGS: Map<String, String> = linkedMapOf(
        "sendPayloadMapping" to "in",
        "dataObjectDataTableCreatePayloadMapping" to "in",
        "headerPropertyMapping" to "in",
        "responsePayloadMapping" to "out",
        "errorResponsePayloadMapping" to "error-out",
    )

    /**
     * A button's payload mappings, normalised onto the same `source -> target` record as a BPMN/CMMN task's
     * (see [XmlHelpers.readIoParams]).
     *
     * Two shapes occur in the wild: a list of `{name, expression}` — `name` is the payload key sent to (or
     * the form path written by) the call, `expression` the `{{…}}` binding read from the form — and, on a
     * REST button, a bare string that is the whole request body as one expression. `headerPropertyMapping`
     * uses `{name, value}` instead and describes HTTP headers, so it keeps its own `kind`.
     */
    private fun payloadParams(es: Map<String, Any?>, refKind: String?, refKey: Any?): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        fun rec(dir: String, kind: String, source: Any?, target: Any?, expression: Boolean = false) {
            val r = linkedMapOf<String, Any?>("dir" to dir, "kind" to kind, "source" to source, "target" to target)
            if (expression) r["expression"] = true
            if (refKind != null) r["refKind"] = refKind
            if (refKey != null) r["refKey"] = refKey
            out.add(r)
        }
        for ((k, dir) in PAYLOAD_MAPPINGS) {
            val v = es[k] ?: continue
            val kind = if (k == "headerPropertyMapping") "header" else k
            when (v) {
                // the whole body as one expression — there is no per-key mapping to show
                is String -> if (v.isNotEmpty()) rec(dir, kind, v, null, expression = true)
                is List<*> -> for (e in listOfObjs(v)) {
                    val src = e["expression"] ?: e["value"]
                    if (e["name"] == null && src == null) continue
                    rec(dir, kind, src, e["name"], expression = src is String && src.contains("{{"))
                }
            }
        }
        return out
    }

    /**
     * The `extraSettings` keys worth showing per button flavour — the ones that change what pressing it
     * does.
     *
     * Per flavour on purpose: the palettes share one `extraSettings` bag and a stencil inherits keys its
     * runtime never reads. `script` is honoured by the expression button alone, yet 32 of 53 action
     * buttons in one real project carry a non-empty one — listing it there would state a behaviour that
     * does not exist. A button's endpoint is not here either: it is already a [restCalls] entry keyed by
     * the same element id.
     */
    private val BUTTON_SETTINGS: Map<String, List<String>> = mapOf(
        "scriptButton" to listOf("script", "timer", "autoExecute", "executeAlways"),
        "restButton" to listOf("method", "path", "valueExpression", "autoExecute", "executeAlways"),
        "workAction" to listOf("navigationUrl", "scopeType", "scopeId", "scopeDefinitionId",
            "invokeActionUrl", "autoExecute", "executeAlways"),
        "workInvokeService" to listOf("invokeServiceUrl", "autoExecute", "executeAlways"),
        "workAgentButton" to listOf("autoExecute", "executeAlways"),
        "createInstanceButton" to listOf("navigationUrl", "autoExecute", "executeAlways"),
        "workUserEventListenerButton" to listOf("autoExecute", "executeAlways"),
        "linkButton" to listOf("target"),
        "outcomeButton" to listOf("keepInForm"),
    )

    /** Node-level (not `extraSettings`) flags a reader cares about, on any button. */
    private val BUTTON_NODE_FLAGS = listOf("primary", "ignoreValidation", "ignorePayload")

    /**
     * The flavour settings of one button, or null when it has none worth naming. `false` is the palette
     * default on almost every flag, so only truthy values survive.
     */
    private fun buttonSettings(n: Map<String, Any?>, es: Map<String, Any?>): Map<String, Any?>? {
        val type = (n["type"] as? String)?.takeIf { it in ModelJsonReader.BUTTON_TYPES } ?: return null
        val out = linkedMapOf<String, Any?>()
        for (k in BUTTON_SETTINGS[type].orEmpty()) if (truthy(es[k])) out[k] = es[k]
        for (k in BUTTON_NODE_FLAGS) if (truthy(n[k])) out[k] = n[k]
        return out.ifEmpty { null }
    }

    /**
     * Whether a component renders, can be used, and is submitted — recorded whenever the model departs
     * from the default (`visible`/`enabled` true, `ignore` false), never when it agrees with it.
     *
     * All three are `boolean | string` in the form runtime: a literal settles the question (`visible:
     * false` is a component that is simply never there), an expression makes it conditional. Both are
     * worth saying and they are not the same statement, so the value is kept as the model spells it.
     * `ignore` excludes the component's binding from the payload — the value is computed and discarded.
     */
    private fun gatingOf(n: Map<String, Any?>): MutableMap<String, Any?>? {
        val out = linkedMapOf<String, Any?>()
        fun gate(field: String, default: Boolean) {
            val v = n[field] ?: return
            if (v != default) out[field] = v
        }
        gate("visible", true)
        gate("enabled", true)
        gate("ignore", false)
        return out.ifEmpty { null }
    }

    /**
     * The caption a localised component carries. Design keeps per-locale overrides of `label` /
     * `extraSettings.text` under `i18n.<locale>`, and on many buttons that is the *only* place a caption
     * exists (166 of 178 expression buttons in one real project carry one). The first locale wins: Atlas
     * is naming the component for a reader, not rendering it for an end user.
     */
    private fun i18nCaption(n: Map<String, Any?>): Any? {
        for (loc in objOf(n["i18n"])?.values.orEmpty()) {
            val m = objOf(loc) ?: continue
            val t = pyOr(m["label"], objOf(m["extraSettings"])?.get("text"))
            if (truthy(t)) return t
        }
        return null
    }

    /**
     * Which of a button's two payload sides is actually in force, when a flag overrides the mapping.
     *
     * The Work runtime picks exactly one on each side: `sendFullPayload` (the whole form payload) beats
     * `sendFullScope` (the surrounding scope) beats the explicit `sendPayloadMapping`; coming back,
     * `mapFullResponse` (every response key, into the scope when `mapResponseInsideScope`) beats
     * `responsePayloadMapping`. An overridden mapping is never read — so Atlas must not present it as the
     * contract. Recorded only when a flag *is* set; otherwise the mappings speak for themselves.
     */
    private fun payloadModes(es: Map<String, Any?>): Map<String, Any?>? {
        val send = when {
            truthy(es["sendFullPayload"]) -> "full-payload"
            truthy(es["sendFullScope"]) -> "full-scope"
            else -> null
        }
        val receive = when {
            !truthy(es["mapFullResponse"]) -> null
            truthy(es["mapResponseInsideScope"]) -> "full-response-in-scope"
            else -> "full-response"
        }
        if (send == null && receive == null) return null
        return linkedMapOf<String, Any?>().apply {
            if (send != null) put("send", send)
            if (receive != null) put("receive", receive)
        }
    }

    /** A model reference: a bare key, or the `{key, id}` object the newer Design editor writes. */
    private fun modelRefKey(v: Any?): Any? = if (v is Map<*, *>) v["key"] else v

    /** The variable root a `{{binding}}` names, or null when the value is an expression, not a target. */
    private fun bindingRoot(value: Any?): String? {
        val raw = (value as? String)?.trim() ?: return null
        if (!raw.startsWith("{{") || !raw.endsWith("}}")) return null
        val inner = raw.removeSurrounding("{{", "}}").trim()
        if (inner.isEmpty() || !inner.matches(Regex("[A-Za-z_$][A-Za-z0-9_.\\[\\]$]*"))) return null
        val root = inner.trimStart('$').substringBefore('.').substringBefore('[')
        return root.takeIf { it.isNotEmpty() && it !in FIELD_ID_IGNORE }
    }

    /** What a button invokes, as `(kind, key)` — the callee its payload is mapped onto. */
    private fun calleeOf(es: Map<String, Any?>, n: Map<String, Any?>): Pair<String?, Any?> {
        if (truthy(es["actionDefinitionKey"])) return "action" to modelRefKey(es["actionDefinitionKey"])
        objOf(es["agentModel"])?.get("agentModelKey")?.let { if (truthy(it)) return "agent" to it }
        objOf(es["serviceModel"])?.get("serviceModelKey")?.let { if (truthy(it)) return "service" to it }
        if (truthy(es["dataObjectDefinitionKey"])) return "dataObject" to es["dataObjectDefinitionKey"]
        // A create-instance button's callee is the process or case it starts — which is also the model
        // whose contract its payload map has to match.
        modelRefKey(es["processReference"])?.let { if (truthy(it)) return "process" to it }
        modelRefKey(es["caseReference"])?.let { if (truthy(it)) return "case" to it }
        // `extraSettings.url` is where a real REST button keeps its endpoint; the component-level `url`
        // only occurs in hand-written and legacy models. Without the former, a REST button's payload and
        // header mappings carried no callee at all.
        val url = pyOr(pyOr(es["url"], n["url"]), es["invokeServiceUrl"]) as? String
        if (!url.isNullOrBlank()) return "rest" to url.trim()
        return null to null
    }

    /** `.agent` — model settings, tools, operations, knowledge base; records tool/KB refs. */
    fun parseAgent(data: ByteArray, ctx: Ctx, ffile: String): Map<String, Any?> {
        val doc = json(data)
        val key = doc["key"]
        val ms = objOf(doc["modelSettings"]) ?: emptyMap()
        val tools = ArrayList<Any?>()
        val operations = ArrayList<Any?>()
        val info = linkedMapOf<String, Any?>(
            "key" to key, "name" to doc["name"], "file" to ffile, "type" to doc["type"],
            "aiVendor" to ms["aiVendor"], "modelName" to ms["modelName"], "temperature" to ms["temperature"],
            "operations" to operations, "tools" to tools, "knowledgeBase" to null, "enableApiEndpoint" to doc["enableApiEndpoint"],
        )
        fun toolRef(t: Any?) {
            val tm = objOf(t) ?: return
            if (!truthy(tm["key"])) return
            val mt = (tm["modelType"] as? String) ?: "service"
            val tool = linkedMapOf<String, Any?>("key" to tm["key"], "type" to mt)
            if (truthy(tm["operationKey"])) tool["operation"] = tm["operationKey"]
            tools.add(tool)
            ctx.addRef(key, "agent", ffile, "tool", mt, tm["key"])
            // a service tool names the exact operation — count it as a use of that operation
            if (mt == "service") ctx.addOpUse(key, "service", tm["key"], tm["operationKey"])
        }
        // freemarker behavior templates (documentClassification + operations), guardrails and
        // evaluators — parity with the platform's AgentModelReferenceExtractor (both persisted
        // shapes: `agentModel.key` directly and nested under `configuration`).
        fun behaviorTemplateRefs(behavior: Map<String, Any?>?) {
            if (behavior?.get("type") != "freemarkerTemplate") return
            for (t in listOf("systemMessageTemplate", "userMessageTemplate")) {
                ctx.addRef(key, "agent", ffile, "message-template", "template", objOf(behavior[t])?.get("templateKey"))
            }
        }
        fun guardrailEvaluatorRefs(node: Map<String, Any?>) {
            for (g in listOfObjs(node["guardrails"])) {
                val type = g["type"] as? String
                val field = when (type) { "agent" -> "agentModel"; "service" -> "serviceModel"; else -> continue }
                val ref = objOf(g[field]) ?: objOf(objOf(g["configuration"])?.get(field))
                ctx.addRef(key, "agent", ffile, "guardrail", type, ref?.get("key"))
            }
            for (ev in listOfObjs(node["evaluators"])) {
                val type = ev["type"] as? String
                if (type == "agent" || type == "service") {
                    ctx.addRef(key, "agent", ffile, "evaluator", type, objOf(ev["reference"])?.get("key"))
                }
            }
        }
        for (t in (doc["tools"] as? List<*> ?: emptyList<Any?>())) toolRef(t)
        guardrailEvaluatorRefs(doc)
        for (op in listOfObjs(doc["operations"])) {
            val beh = objOf(op["behavior"]) ?: emptyMap()
            operations.add(linkedMapOf(
                "key" to op["key"], "name" to op["name"],
                "systemMessage" to ((beh["systemMessage"] as? String) ?: "").take(200),
                "userMessage" to ((beh["userMessage"] as? String) ?: "").take(200),
            ))
            for (t in (op["tools"] as? List<*> ?: emptyList<Any?>())) toolRef(t)
            behaviorTemplateRefs(beh)
            guardrailEvaluatorRefs(op)
        }
        // document classification: freemarker templates + classified document content models
        val dc = objOf(doc["documentClassification"])
        if (dc != null) {
            behaviorTemplateRefs(objOf(dc["behavior"]))
            for (d in listOfObjs(dc["documentClassifications"])) {
                ctx.addRef(key, "agent", ffile, "classifies-document", "document", objOf(d["contentModel"])?.get("key"))
            }
        }
        // external agent settings: inbound-event-configuration properties reference an event model
        for (v in (objOf(objOf(doc["externalAgentSettings"])?.get("properties")) ?: emptyMap()).values) {
            ctx.addRef(key, "agent", ffile, "agent-event", "event", objOf(v)?.get("key"))
        }
        val kb = objOf(objOf(doc["knowledgeBase"])?.get("knowledgeBaseModelReference")) ?: emptyMap()
        if (truthy(kb["key"])) { info["knowledgeBase"] = kb["key"]; ctx.addRef(key, "agent", ffile, "knowledgeBase", "knowledgeBase", kb["key"]) }
        val da = objOf(objOf(doc["documentAgent"])?.get("documentAgentModel")) ?: emptyMap()
        if (truthy(da["key"])) ctx.addRef(key, "agent", ffile, "documentAgent", "agent", da["key"])
        return info
    }

    /** `.channel` — inbound/outbound channel + its event-key detection; records channel→event. */
    fun parseChannel(data: ByteArray, ctx: Ctx, ffile: String): Map<String, Any?> {
        val doc = json(data)
        val ek = objOf(doc["channelEventKeyDetection"]) ?: emptyMap()
        ctx.addRef(doc["key"], "channel", ffile, "channel-event", "event", ek["fixedValue"])
        return linkedMapOf(
            "key" to doc["key"], "name" to doc["name"], "file" to ffile,
            "channelType" to doc["channelType"], "type" to doc["type"],
            "topics" to doc["topics"], "destination" to doc["destination"], "eventKey" to ek,
        )
    }

    /** `.action` — a bot/action model; records form/channel/signal refs + script vars. */
    fun parseAction(data: ByteArray, ctx: Ctx, ffile: String): Map<String, Any?> {
        val doc = json(data)
        val key = doc["key"]
        ctx.addRef(key, "action", ffile, "action-form", "form", doc["formKey"])
        for (ch in (doc["channels"] as? List<*> ?: emptyList<Any?>())) {
            ctx.addRef(key, "action", ffile, "action-channel", "channel", if (ch is String) ch else objOf(ch)?.get("key"))
        }
        // `signalName` is a model key only for the start-instance bots (the platform's reference
        // extractor discriminates on botKey the same way); for any other bot it is a real BPMN
        // signal name and resolves against the signal index, not the process index.
        when (doc["botKey"]) {
            "bpmn-start-process-instance-bot" ->
                ctx.addRef(key, "action", ffile, "starts-process", "process", doc["signalName"])
            "cmmn-start-case-instance-bot" ->
                ctx.addRef(key, "action", ffile, "starts-case", "case", doc["signalName"])
            else ->
                ctx.addRef(key, "action", ffile, "triggers-signal", "signal", doc["signalName"])
        }
        val permGroups = (doc["permissionGroups"] as? List<*>) ?: emptyList<Any?>()
        ctx.addAccess(key, "action", "action", "use", permGroups.joinToString(","))
        val config = objOf(doc["config"]) ?: emptyMap()
        val scriptInfo = objOf(config["scriptInfo"]) ?: emptyMap()
        val script = scriptInfo["script"] as? String
        VarHarvest.collectScriptVars(ctx, script, listOf(key), scriptInfo["language"] as? String)
        val rec = linkedMapOf(
            "key" to key, "name" to doc["name"], "file" to ffile, "botKey" to doc["botKey"],
            "formKey" to doc["formKey"], "signalName" to doc["signalName"], "channels" to doc["channels"],
            "scopeType" to doc["scopeType"], "icon" to doc["icon"], "permissionGroups" to doc["permissionGroups"],
            "script" to script, "scriptLanguage" to scriptInfo["language"],
            "ioParameters" to actionParams(ctx, key, doc, config, script),
        )
        // named scriptProblems, not problems: the action record is the node's data wholesale, and
        // node.data.problems already means "expression problems" to the explorer
        ScriptValidator.problemDicts(script, scriptInfo["language"] as? String,
            context = ScriptContext.ACTION_BOT)
            .ifEmpty { null }?.let { rec["scriptProblems"] = it }
        return rec
    }

    /** `flw.getInput('x')` / `flw.setOutput('y', …)` — how a script-based action bot reads its payload
     *  and returns a result (the `flw` scripting API; quotes may be single or double). */
    private val FLW_IO_RE = Regex("""flw\.(getInput|setOutput)\s*\(\s*['"]([^'"]+)['"]""")

    /**
     * The in/out parameters an action bot is given, in the same shape as a BPMN/CMMN task's mappings.
     *
     * An action has no extension elements — its inputs arrive three ways: `signalVariableNames` (copied
     * into the signalled instance), the bot-specific `config` block, and, for script bots, the `flw`
     * scripting API. `element` is the bot key so the reader can see *which* bot the values feed.
     */
    private fun actionParams(
        ctx: Ctx, key: Any?, doc: Map<String, Any?>, config: Map<String, Any?>, script: String?,
    ): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        val bot = doc["botKey"]
        // the bot is this action's callee — the thing the values are actually handed to
        val callee = (bot as? String)?.ifEmpty { null }?.let { "bot" to it }
        fun add(dir: String, kind: String, source: Any?, target: Any?) = ctx.addParams(
            out, key, bot, doc["name"], "actionBot",
            listOf(linkedMapOf("dir" to dir, "kind" to kind, "source" to source, "target" to target)),
            callee = callee,
        )
        for (v in (doc["signalVariableNames"] as? List<*> ?: emptyList<Any?>())) {
            if (v is String && v.isNotEmpty()) add("in", "signalVariable", null, v)
        }
        // Only scalar config entries are a parameter; a nested object (e.g. `scriptInfo`) is bot wiring.
        for ((k, v) in config) {
            if (v is Map<*, *> || v is List<*>) continue
            add("in", "config", v?.toString(), k)
        }
        // A script commonly reads the same input in several places; list each payload key once.
        val seen = LinkedHashSet<Pair<String, String>>()
        for (m in FLW_IO_RE.findAll(script ?: "")) {
            val dir = if (m.groupValues[1] == "getInput") "in" else "out"
            if (seen.add(dir to m.groupValues[2])) add(dir, "flwScript", null, m.groupValues[2])
        }
        return out
    }

    /** `.dictionary` — a data dictionary's declared type names. */
    fun parseDictionary(data: ByteArray, ctx: Ctx, ffile: String): Map<String, Any?> {
        val doc = json(data)
        val types = objOf(doc["types"]) ?: emptyMap()
        return linkedMapOf("key" to doc["key"], "name" to doc["name"], "file" to ffile, "types" to types.keys.toList())
    }

    /** `.data` (data object / masterData) — columns, backing service/dictionary, relations, access. */
    fun parseDataObject(data: ByteArray, ctx: Ctx, ffile: String): Map<String, Any?> {
        val doc = json(data)
        val key = doc["key"]
        (objOf(doc["definitionIdentityLinks"]) ?: emptyMap()).forEach { (action, links) ->
            val lm = objOf(links) ?: return@forEach
            ctx.addAccess(key, "dataObject", "definition", action, (lm["groups"] as? List<*>)?.joinToString(",") ?: "")
        }
        for (il in listOfObjs(doc["instanceIdentityLinks"])) {
            ctx.addAccess(key, "dataObject", "instance", (il["type"] as? String) ?: "link", (il["groups"] as? List<*>)?.joinToString(",") ?: "")
        }
        ctx.addRef(key, "dataObject", ffile, "backed-by-service", "service", doc["referencedServiceDefinitionModelKey"])
        ctx.addRef(key, "dataObject", ffile, "typed-by-dictionary", "dataDictionary", doc["referencedDataDictionaryModelKey"])
        val columns = ArrayList<Map<String, Any?>>()
        for (f in listOfObjs(doc["fieldMappings"])) {
            val col = linkedMapOf<String, Any?>("name" to f["name"], "label" to f["label"], "type" to f["type"])
            if (truthy(f["dataObjectModelKey"])) {
                col["refDataObject"] = f["dataObjectModelKey"]
                col["relationship"] = f["dataObjectModelRelationshipType"]
                ctx.addRef(key, "dataObject", ffile, "relates-to", "dataObject", f["dataObjectModelKey"])
            }
            columns.add(col)
        }
        objOf(doc["variables"])?.forEach { (n, lbl) ->
            columns.add(linkedMapOf("name" to n, "label" to (lbl as? String), "type" to null))
            ctx.addVar(key, n)
            // A data object's own variable is a column of a table the Work UI, a query or a REST client
            // reads. Nothing in the models has to mention it for it to be in use, so the direction is
            // recorded as undecidable rather than as a write nobody consumes.
            ctx.markReadsUnknown(n)
        }
        val out = linkedMapOf<String, Any?>(
            "key" to key, "name" to doc["name"], "file" to ffile,
            "dataObjectType" to doc["dataObjectType"], "sourceId" to doc["sourceId"],
            "service" to doc["referencedServiceDefinitionModelKey"], "dictionary" to doc["referencedDataDictionaryModelKey"],
            "columns" to columns, "fields" to columns.map { it["name"] },
        )
        for (k in listOf("type", "subType", "keyField", "idField", "nameField", "supportsNameFiltering")) {
            if (doc[k] != null) out[k] = doc[k]
        }
        return out
    }

    /** Fallback for model types without a dedicated parser (query/sequence/sla/template/…) → `others`. */
    fun parseGeneric(data: ByteArray, ctx: Ctx, ffile: String, mtype: String): Map<String, Any?> {
        val doc = try { MiniJson.parse(String(data, Charsets.UTF_8)) } catch (e: Exception) { null }
        if (doc !is Map<*, *>) return linkedMapOf("key" to null, "name" to null, "file" to ffile, "modelType" to mtype)
        @Suppress("UNCHECKED_CAST") val d = doc as Map<String, Any?>
        val out = linkedMapOf<String, Any?>("file" to ffile, "modelType" to mtype)
        for (k in GENERIC_KEYS) if (truthy(d[k])) out[k] = d[k]
        objOf(d["queryModel"])?.let { qm -> if (truthy(qm["key"])) ctx.addRef(d["key"], mtype, ffile, "queryModel", "query", qm["key"]) }
        if (mtype == "variableExtractor") for (ve in listOfObjs(d["variableExtractors"])) {
            ctx.addRef(d["key"], mtype, ffile, "extracts-from", "process", objOf(ve["filter"])?.get("scopeDefinitionKey"))
            ctx.addVar(d["key"], ve["to"])
            // An extracted variable exists precisely so that queries, task lists and dashboards can
            // index it — none of which Atlas parses. It is a write whose readers are out of reach.
            ctx.addVarSite(d["key"], ve["to"], Ctx.WRITE, "variableExtractor", elementType = "variableExtractor")
            ctx.markReadsUnknown(ve["to"])
        }
        if (mtype == "template" && truthy(d["formKey"])) ctx.addRef(d["key"], mtype, ffile, "template-form", "form", d["formKey"])
        if (mtype == "document") objOf(d["forms"])?.forEach { (op, fk) -> ctx.addRef(d["key"], mtype, ffile, "document-$op-form", "form", fk) }
        return out
    }

    /** Python `a or b`: `a` when truthy, else `b`. */
    private fun pyOr(a: Any?, b: Any?): Any? = if (truthy(a)) a else b

    /** Python truthiness for a permission flag (true / non-empty / non-zero). */
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
