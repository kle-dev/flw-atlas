package com.flowable.atlas.parsing

import com.flowable.atlas.graph.Ctx
import com.flowable.atlas.model.MiniJson

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
        // per-operation input/output parameters); operation body templates → template model.
        ctx.addRef(doc["key"], "service", ffile, "service-dataObject", "dataObject", doc["referenceKey"])
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
                "url" to oc["url"], "fullUrl" to full, "params" to operationParams(op),
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

    /** Input parameters an operation declares (the variables it requires), name + type. */
    private fun operationParams(op: Map<String, Any?>): List<Map<String, Any?>> =
        listOfObjs(op["inputParameters"]).filter { it["name"] != null }
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
        val info = linkedMapOf<String, Any?>(
            "key" to key, "name" to meta["name"], "file" to ffile, "modelType" to mtype,
            "fields" to fields, "outcomes" to outcomes, "dataSources" to dataSources, "subforms" to subforms,
        )
        for (oc in listOfObjs(doc["outcomes"])) {
            outcomes.add(linkedMapOf("value" to oc["value"], "label" to oc["label"]))
            ctx.addRef(key, mtype, ffile, "outcome-form", "form", oc["outcomeFormKey"])
        }
        fun visit(n: Map<String, Any?>) {
            if (truthy(n["id"]) && n.containsKey("type") && n.containsKey("label")) {
                fields.add(linkedMapOf(
                    "id" to n["id"], "type" to n["type"], "label" to n["label"],
                    "required" to (n["isRequired"] ?: false), "value" to n["value"],
                ))
                if (n["type"] == "outcomeButton" && truthy(n["value"])) {
                    outcomes.add(linkedMapOf("value" to n["value"], "label" to n["label"]))
                }
            }
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
                if (truthy(es["queryUrl"])) {
                    dataSources.add(linkedMapOf("kind" to "rest", "url" to es["queryUrl"]))
                    ctx.restCalls.add(linkedMapOf("source" to key, "sourceFile" to ffile, "where" to n["id"], "method" to "GET", "url" to es["queryUrl"], "kind" to "form-query"))
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
                if (truthy(es["actionDefinitionKey"])) ctx.addRef(key, mtype, ffile, "triggers-action", "action", es["actionDefinitionKey"])
                // Data-source / lookup / navigation URLs (queryUrl, lookupUrl, navigationUrl, …) can embed a
                // dataObject/service operation as literal query params — pick those up as op-uses.
                for (v in es.values) if (v is String) recordUrlOpUses(v, key, mtype, ffile, ctx)
            }
            val u = n["url"]
            if (u is String && u.trim().isNotEmpty()) {
                ctx.restCalls.add(linkedMapOf("source" to key, "sourceFile" to ffile, "where" to n["id"], "method" to "(button)", "url" to u.trim(), "kind" to "form-button"))
                recordUrlOpUses(u, key, mtype, ffile, ctx)
            }
            // Link components carry their target URL in `value`.
            (n["value"] as? String)?.let { recordUrlOpUses(it, key, mtype, ffile, ctx) }
        }
        walkJson(doc["rows"] ?: emptyList<Any?>(), ::visit)
        return info
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
            tools.add(linkedMapOf("key" to tm["key"], "type" to mt))
            ctx.addRef(key, "agent", ffile, "tool", mt, tm["key"])
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
        val scriptInfo = objOf(objOf(doc["config"])?.get("scriptInfo")) ?: emptyMap()
        val script = scriptInfo["script"] as? String
        VarHarvest.collectScriptVars(ctx, script, listOf(key))
        return linkedMapOf(
            "key" to key, "name" to doc["name"], "file" to ffile, "botKey" to doc["botKey"],
            "formKey" to doc["formKey"], "signalName" to doc["signalName"], "channels" to doc["channels"],
            "scopeType" to doc["scopeType"], "icon" to doc["icon"], "permissionGroups" to doc["permissionGroups"],
            "script" to script, "scriptLanguage" to scriptInfo["language"],
        )
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
        }
        if (mtype == "template" && truthy(d["formKey"])) ctx.addRef(d["key"], mtype, ffile, "template-form", "form", d["formKey"])
        if (mtype == "document") objOf(d["forms"])?.forEach { (op, fk) -> ctx.addRef(d["key"], mtype, ffile, "document-$op-form", "form", fk) }
        return out
    }

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
