package com.flowable.atlas.graph

import com.flowable.atlas.expr.ExprProblem
import com.flowable.atlas.expr.ExprProblemKind
import com.flowable.atlas.expr.ExprSeverity
import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.expr.ExpressionValidator
import com.flowable.atlas.expr.ExprWrappers
import com.flowable.atlas.expr.catalog.CustomFunctionCatalog
import com.flowable.atlas.parsing.Constants
import com.flowable.atlas.parsing.ModelKinds

/**
 * The navigable-graph builder — a faithful port of `flowable_atlas.py` `_build_graph`
 * (~lines 3547-4004) plus the surrounding `result["graph"] = …` / `result["stats"] = …` assignment
 * done in `extract` (~lines 1586-1607).
 *
 * It turns the parsed models, resolved references, Java index, expressions/bindings/variables and
 * REST calls into a `{"nodes": [...], "edges": [...]}` graph, MUTATES each model/java object in place
 * to add its reverse `_uses` map (so the `apps`/`processes`/`cases`/`forms`/`dataObjects` buckets gain
 * `_uses`), POPS the internal `_matchEps` key from each `restCalls` entry, and finally sets
 * `result["graph"]` and `result["stats"]`.
 *
 * Node types produced: model kinds (`app`/`process`/`case`/`decision`/`form`/`page`/`dataObject`/
 * `service`/…), `liquibase`, `java`, `endpoint`, `group`, `expression`, `binding`, `variable`,
 * `string`, `customFunction`, `method`, `external`, `bot`.
 */
object GraphBuilder {

    // ---- module-level regexes / name-sets (ported from flowable_atlas.py ~lines 72-111, 2820) ----
    private val STR_LIT_RE = Regex("'([^']*)'|\"([^\"]*)\"")
    private val ROOT_IDENT_RE = Regex("(?<![\\w.\$])([A-Za-z_][\\w]*)")
    private val EXPR_STRIP_RE = Regex("^[#$]\\{|\\}$")
    private val STR_IN_EXPR_RE = Regex("'[^']*'|\"[^\"]*\"")
    private val MUSTACHE_HEAD_RE = Regex("^\\$?([A-Za-z_][\\w]*)")
    private val DATAOBJ_QUERY_RE = Regex("dataObjectDefinitionKey=|/query/")
    private val ENDPOINTS_PLATFORM_RE = Regex("(?:^|[/{\$\\s])endpoints\\.")

    /** Model types rendered as Freemarker (not JUEL) — their `${…}` must not be validated. */
    private val FREEMARKER_MODEL_TYPES = setOf("query", "template", "document")

    /** Well-known Flowable platform service-task beans (engine-provided, not project source). */
    private val FLOWABLE_PLATFORM_BEANS = setOf(
        "initVariablesService", "dataObjectServiceTask", "generateDocumentService",
        "createDocumentService", "serviceRegistryService", "agentService",
        "sendEventServiceTask", "auditLogService", "decisionServiceTask",
        "caseServiceTask", "httpServiceTask", "scriptServiceTask", "mailServiceTask",
    )

    /** Ref kinds correlated by NAME (not by model key): throw side and catch side of a signal/
     *  message/error/escalation — and external-worker topics — meet in one shared node. */
    private val NAMED_REF_KINDS = setOf("signal", "message", "error", "escalation", "topic")

    /** Root identifiers a `{{…}}` placeholder may carry that are never project variables. */
    private val MUSTACHE_IGNORE = setOf(
        "endpoints", "item", "index", "ctx", "root", "parent", "event", "self",
        "first", "last", "start", "pageSize", "flw", "payload", "temp", "filter",
        "sortColumn", "sortDirection", "orderBy", "sortBy", "total", "response",
        "page", "size", "data", "value", "params",
    )

    /**
     * Build the graph, enrich models with `_uses`, pop `_matchEps` and set `result["graph"]` /
     * `result["stats"]`. Mirrors `_build_graph(result, ctx, resolved, all_java, bean_methods, by_key,
     * expr_allowlist, custom)` followed by the `result.update({... "graph": …, "stats": …})` in
     * `extract`.
     */
    @Suppress("UNCHECKED_CAST")
    fun build(
        result: LinkedHashMap<String, Any?>,
        ctx: Ctx,
        resolved: List<Map<String, Any?>>,
        allJava: Map<String, Map<String, Any?>>,
        beanMethods: Map<String, Set<String>>,
        byKey: LinkedHashMap<String, MutableList<Pair<String, String>>>,
        exprAllowlist: Set<String>? = null,
        custom: CustomFunctionCatalog? = null,
    ) {
        fun bucketList(name: String): List<Any?> = result[name] as? List<Any?> ?: emptyList()

        val nodes = LinkedHashMap<String, LinkedHashMap<String, Any?>>()
        val keyToNode = LinkedHashMap<String, String>()

        fun addNode(ntype: String, key: Any?, label: Any?, file: Any?, data: Any?): String? {
            if (key == null) return null
            val nid = "$ntype:$key"
            if (nid !in nodes) {
                nodes[nid] = linkedMapOf(
                    "id" to nid, "type" to ntype, "label" to (if (truthy(label)) label else key),
                    "key" to key, "file" to file, "data" to data,
                )
            }
            keyToNode.putIfAbsent(key.toString(), nid)
            return nid
        }

        fun kn(key: Any?): String? = (key as? String)?.let { keyToNode[it] }

        // --- model nodes from buckets (explicit semantic order — key_to_node is first-wins). ---
        val nodeTypeByBucket = ModelKinds.MODEL_KINDS.associate { it.bucket to it.nodeType }
        val modelBucketOrder = listOf(
            "apps", "processes", "cases", "decisions", "forms", "dataObjects",
            "services", "agents", "channels", "events", "dictionaries", "policies", "actions",
        )
        for (bucket in modelBucketOrder) {
            for (o in bucketList(bucket)) {
                val om = o as Map<String, Any?>
                val ntype = if (bucket == "forms") (om["modelType"] as? String ?: "form")
                else nodeTypeByBucket.getValue(bucket)
                addNode(ntype, om["key"], om["name"], om["file"], o)
            }
        }
        for (o in bucketList("liquibase")) {
            val lb = o as Map<String, Any?>
            addNode(
                "liquibase", lb["key"], basename(lb["file"] as? String), lb["file"],
                linkedMapOf(
                    "tables" to lb["tables"], "effectiveTables" to lb["effectiveTables"],
                    "columns" to lb["columns"], "coverage" to lb["coverage"], "authority" to lb["authority"],
                ),
            )
        }
        for (o in bucketList("others")) {
            val om = o as Map<String, Any?>
            addNode(om["modelType"] as? String ?: "other", om["key"], om["name"], om["file"], o)
        }

        // all model keys (for CODE -> MODEL references); liquibase keys are file-derived, so excluded.
        val modelKeys = HashSet<String>()
        for (bucket in ModelKinds.MODEL_BUCKETS) {
            if (bucket == "liquibase") continue
            for (o in bucketList(bucket)) {
                val k = (o as Map<String, Any?>)["key"]
                if (truthy(k)) modelKeys.add(k.toString())
            }
        }

        // --- which java classes are referenced by models + functional roles (delegate / listener) ---
        val referencedJava = HashSet<String>()
        val delegateFqns = HashSet<String>()
        val listenerFqns = HashSet<String>()
        for (r in resolved) {
            val fqn = r["targetFqn"] as? String ?: continue
            if (fqn.isEmpty()) continue
            referencedJava.add(fqn)
            val rel = r["rel"] as String
            if (rel == "serviceTask-delegate" || rel == "task-delegate") delegateFqns.add(fqn)
            else if (rel.startsWith("executionListener") || rel.startsWith("taskListener") ||
                rel.startsWith("planItemLifecycleListener")
            ) listenerFqns.add(fqn)
        }

        fun calledMethodsFor(jc: Map<String, Any?>): List<String> {
            val out = LinkedHashSet<String>()
            val bset = LinkedHashSet<String>()
            bset.addAll(jc["beanNames"] as Collection<String>)
            val primary = jc["primary"] as String
            bset.add(primary.replaceFirstChar { it.lowercase() })
            for (b in bset) out.addAll(beanMethods[b] ?: emptySet())
            return out.sorted()
        }

        fun javaNode(jc: Map<String, Any?>): String? {
            val fqn = jc["fqn"] as String
            val roles = LinkedHashSet(jc["roles"] as Collection<String>)
            if (fqn in delegateFqns) roles.add("delegate")
            if (fqn in listenerFqns) roles.add("listener")
            if (roles.size > 1) roles.remove("other")
            val data = linkedMapOf<String, Any?>(
                "fqn" to fqn, "package" to jc["package"], "file" to jc["file"], "line" to jc["line"],
                "roles" to roles.sorted(), "beanNames" to (jc["beanNames"] as Collection<String>).sorted(),
                "interfaces" to (jc["interfaces"] as Collection<String>).sorted(),
                "endpoints" to jc["endpoints"], "botKey" to jc["botKey"],
                "methods" to (jc["methods"] ?: emptyList<Any?>()), "calledMethods" to calledMethodsFor(jc),
            )
            return addNode("java", fqn, jc["primary"], jc["file"], data)
        }

        // Java classes that invoke a service/data-object operation must become nodes so they resolve
        // as consumers in each operation's "usedBy" list.
        val opUseConsumers = ctx.opUse.mapNotNull { it["consumer"] as? String }.toHashSet()
        for ((fqn, jc) in allJava) {
            val roles = jc["roles"] as Collection<String>
            val vars = jc["vars"] as? Collection<*> ?: emptyList<Any?>()
            val strings = jc["strings"] as? Collection<*> ?: emptyList<Any?>()
            val topics = jc["topics"] as? Collection<*> ?: emptyList<Any?>()
            if (roles.any { it != "other" } || fqn in referencedJava || vars.isNotEmpty() ||
                strings.any { it in modelKeys } || fqn in opUseConsumers || topics.isNotEmpty()
            ) javaNode(jc)
        }

        // index java simple-name -> fqn for dependency (DI) edges; names shared by several classes
        // resolve first-wins, so edges through them are flagged `suspect`.
        val simpleToFqn = LinkedHashMap<String, String>()
        val ambiguousSimple = HashSet<String>()
        for ((fqn, jc) in allJava) {
            if (simpleToFqn.putIfAbsent(jc["primary"] as String, fqn) != null) ambiguousSimple.add(jc["primary"] as String)
        }

        // --- endpoint nodes ---
        for (o in bucketList("endpoints")) {
            val ep = o as Map<String, Any?>
            val key = "${ep["http"]} ${ep["path"]}"
            addNode(
                "endpoint", key, key, ep["file"],
                linkedMapOf(
                    "controller" to ep["controller"], "handler" to ep["handler"], "line" to ep["line"],
                    "http" to ep["http"], "path" to ep["path"],
                ),
            )
        }

        // --- group nodes ---
        for (g in ctx.groups) addNode("group", g, g, null, linkedMapOf<String, Any?>())

        // --- expression / binding nodes (with the models that use them) ---
        fun usageNodes(ntype: String, usage: Map<String, MutableSet<String>>) {
            val dialect = if (ntype == "expression") ExpressionDialect.BACKEND else ExpressionDialect.FRONTEND
            for ((text, keys) in usage) {
                val used = keys.filter { it in keyToNode }.map { keyToNode.getValue(it) }.toSortedSet().toList()
                if (used.isEmpty()) continue
                val data = linkedMapOf<String, Any?>("usedBy" to used)
                val usedTypes = used.mapNotNull { nodes[it]?.get("type") as? String }.toSet()
                if (!FREEMARKER_MODEL_TYPES.containsAll(usedTypes)) {
                    var problems = validateHarvestedExpr(text, dialect, custom)
                    if (problems != null && problems.isNotEmpty() && exprAllowlist != null) {
                        problems = problems.filter { !exprProblemAllowlisted(it, exprAllowlist) }
                    }
                    if (problems != null && problems.isNotEmpty()) data["problems"] = problems
                }
                addNode(ntype, text, text, null, data)
            }
        }
        usageNodes("expression", ctx.exprUse)
        usageNodes("binding", ctx.mustacheUse)

        // --- variable nodes ---
        val beans = LinkedHashSet<String>()
        beans.addAll(FLOWABLE_PLATFORM_BEANS)
        beans.addAll(beanMethods.keys)
        for (r in ctx.refs) if (r["kind"] == "bean") beans.add(r["value"].toString())
        for (jc in allJava.values) {
            beans.addAll((jc["beanNames"] as? Collection<String>) ?: emptyList())
            val primary = jc["primary"] as? String
            if (!primary.isNullOrEmpty()) beans.add(primary.replaceFirstChar { it.lowercase() })
        }

        val varUsages = LinkedHashMap<String, LinkedHashMap<String, LinkedHashSet<String>>>()
        fun addUsage(v: String, k: Any?, snippet: String) {
            if (v in beans) return
            varUsages.getOrPut(v) { LinkedHashMap() }.getOrPut(k.toString()) { LinkedHashSet() }.add(snippet)
        }

        for ((expr, keys) in ctx.exprUse) for (v in varsInExpr(expr)) for (k in keys) addUsage(v, k, expr)
        for ((ph, keys) in ctx.mustacheUse) {
            val v = varInMustache(ph) ?: continue
            for (k in keys) addUsage(v, k, ph)
        }
        for ((v, keys) in ctx.varUse) for (k in keys) addUsage(v, k, "(declared / mapped)")
        for ((v, keys) in ctx.scriptVarUse) for (k in keys) addUsage(v, k, "(script)")
        for (o in bucketList("apps")) {
            val am = o as Map<String, Any?>
            for (vv in (am["variables"] as? List<*> ?: emptyList<Any?>())) {
                val vm = vv as? Map<*, *> ?: continue
                if (truthy(vm["key"])) addUsage(vm["key"].toString(), am["key"], "(app variable)")
            }
        }
        for ((fqn, jc) in allJava) for (v in (jc["vars"] as? Collection<String> ?: emptyList())) {
            addUsage(v, fqn, "(Java: set/getVariable)")
        }

        for ((v, perModel) in varUsages) {
            val usedBy = perModel.keys.filter { it in keyToNode }.map { keyToNode.getValue(it) }.toSortedSet().toList()
            if (usedBy.isEmpty()) continue
            val scopes = usedBy.mapNotNull { nodes[it]?.get("type") as? String }.toSortedSet().toList()
            val usages = perModel.entries.filter { it.key in keyToNode }.map { (k, snips) ->
                linkedMapOf<String, Any?>("model" to keyToNode.getValue(k), "snippets" to snips.sorted().take(10))
            }
            addNode("variable", v, v, null, linkedMapOf("usedBy" to usedBy, "scopes" to scopes, "usages" to usages))
        }

        // --- string-literal nodes ---
        val strUsages = LinkedHashMap<String, LinkedHashMap<String, LinkedHashSet<String>>>()
        for (usage in listOf(ctx.exprUse, ctx.mustacheUse)) {
            for ((text, keys) in usage) {
                for (m in STR_LIT_RE.findAll(text)) {
                    val lit = m.groups[1]?.value ?: m.groups[2]?.value
                    if (lit == null || lit.isBlank()) continue
                    for (k in keys) strUsages.getOrPut(lit) { LinkedHashMap() }.getOrPut(k) { LinkedHashSet() }.add(text)
                }
            }
        }
        for ((lit, perModel) in strUsages) {
            val usedBy = perModel.keys.filter { it in keyToNode }.map { keyToNode.getValue(it) }.toSortedSet().toList()
            if (usedBy.isEmpty()) continue
            val usages = perModel.entries.filter { it.key in keyToNode }.map { (k, snips) ->
                linkedMapOf<String, Any?>("model" to keyToNode.getValue(k), "snippets" to snips.sorted().take(10))
            }
            addNode("string", lit, lit, null, linkedMapOf("usedBy" to usedBy, "usages" to usages))
        }

        // --- custom-function nodes (externals.additionalData) ---
        if (custom != null) {
            val sigs = custom.signatures
            val cfnUsed = LinkedHashMap<String, LinkedHashSet<String>>()
            val cfnBindings = LinkedHashMap<String, LinkedHashSet<String>>()
            for ((text, keys) in ctx.mustacheUse) {
                val called = customFnsCalledIn(text, custom)
                if (called.isEmpty()) continue
                val bnode = "binding:$text"
                val callIds = ArrayList<String>()
                for (disp in called.sorted()) {
                    callIds.add("customFunction:$disp")
                    for (k in keys) kn(k)?.let { cfnUsed.getOrPut(disp) { LinkedHashSet() }.add(it) }
                    if (bnode in nodes) cfnBindings.getOrPut(disp) { LinkedHashSet() }.add(bnode)
                }
                if (bnode in nodes && callIds.isNotEmpty()) {
                    (nodes.getValue(bnode)["data"] as MutableMap<String, Any?>)["calls"] = callIds.toSortedSet().toList()
                }
            }
            for ((disp, kind, ns, member) in customFunctionEntries(custom)) {
                val params = sigs[disp]
                val label = if (params != null) "$disp($params)" else disp
                addNode(
                    "customFunction", disp, label, null,
                    linkedMapOf(
                        "kind" to kind, "namespace" to ns, "member" to member, "signature" to params,
                        "sources" to custom.sources, "usedBy" to (cfnUsed[disp]?.sorted() ?: emptyList<String>()),
                        "bindings" to (cfnBindings[disp]?.sorted() ?: emptyList<String>()),
                    ),
                )
            }
        }

        // --- service-operation nodes (where each operation is used) ---
        // Invert Ctx.opUse (consumer -> service/dataObject + operation) into operation -> consumer node
        // ids, resolving data-object references to their backing service, then promote every operation of
        // every service to its own node carrying that `usedBy` list (mirrors the custom-function block).
        run {
            val doToService = LinkedHashMap<String, String>()
            for (o in bucketList("dataObjects")) {
                val dobj = o as Map<String, Any?>
                val svc = dobj["service"]
                if (truthy(dobj["key"]) && truthy(svc)) doToService[dobj["key"].toString()] = svc.toString()
            }
            val opUsedBy = LinkedHashMap<String, LinkedHashSet<String>>()
            for (u in ctx.opUse) {
                val op = u["op"]?.toString() ?: continue
                val targetKey = u["targetKey"]?.toString() ?: continue
                val svcKey = if (u["targetKind"] == "dataObject") doToService[targetKey] else targetKey
                if (svcKey == null) continue
                val consumerNode = kn(u["consumer"]) ?: continue
                opUsedBy.getOrPut("$svcKey#$op") { LinkedHashSet() }.add(consumerNode)
            }
            for (o in bucketList("services")) {
                val svc = o as Map<String, Any?>
                val svcKey = svc["key"] ?: continue
                for (opAny in (svc["operations"] as? List<*> ?: emptyList<Any?>())) {
                    val op = opAny as? Map<String, Any?> ?: continue
                    val opKey = op["key"] ?: continue
                    addNode(
                        "serviceOperation", "$svcKey#$opKey", opKey, null,
                        linkedMapOf(
                            "service" to svcKey, "operation" to opKey, "name" to op["name"],
                            "method" to op["method"], "url" to op["url"], "fullUrl" to op["fullUrl"],
                            "params" to op["params"], "usedBy" to (opUsedBy["$svcKey#$opKey"]?.sorted() ?: emptyList<String>()),
                        ),
                    )
                }
            }
        }

        // Reverse direction: attach to each model the artifacts it uses (vars/exprs/...).
        val artifactTypes = setOf("variable", "expression", "binding", "string", "customFunction", "serviceOperation")
        for (n in nodes.values.toList()) {
            if (n["type"] !in artifactTypes) continue
            val data = n["data"] as? Map<String, Any?> ?: continue
            for (muid in (data["usedBy"] as? List<*> ?: emptyList<Any?>())) {
                val mnode = nodes[muid] ?: continue
                val mdata = mnode["data"] as? MutableMap<String, Any?> ?: continue
                val uses = mdata.getOrPut("_uses") { LinkedHashMap<String, MutableList<String>>() }
                        as MutableMap<String, MutableList<String>>
                uses.getOrPut(n["type"] as String) { ArrayList() }.add(n["id"] as String)
            }
        }
        for (n in nodes.values) {
            val data = n["data"] as? MutableMap<String, Any?> ?: continue
            if (data.containsKey("_uses")) {
                val uses = data["_uses"] as Map<String, List<String>>
                data["_uses"] = uses.mapValuesTo(LinkedHashMap()) { it.value.sorted() }
            }
        }

        // ------------------------------------------------------------------ edges
        val edges = ArrayList<LinkedHashMap<String, Any?>>()
        fun addEdge(s: String?, t: String?, rel: String, suspect: Boolean = false, dynamic: Boolean = false) {
            if (!s.isNullOrEmpty() && !t.isNullOrEmpty() && s != t) {
                val e = linkedMapOf<String, Any?>("s" to s, "t" to t, "rel" to rel)
                if (suspect) e["suspect"] = true
                if (dynamic) e["dynamic"] = true
                edges.add(e)
            }
        }

        // model -> model / java (a `suspect` ref — incompatible cross-type fallback, ref-by-id,
        // ambiguous simple-name match — keeps the flag on its edge so the explorer can mark it)
        for (r in resolved) {
            val s = kn(r["from"])
            val t: String? = when {
                r["targetType"] == "model" -> kn(r["value"])
                truthy(r["targetFqn"]) -> "java:${r["targetFqn"]}"
                else -> null
            }
            addEdge(s, t, r["rel"] as String, suspect = r["suspect"] == true)
        }

        // Java methods called from models: model --calls--> method --declared-in--> class
        val methodsCalled = LinkedHashMap<String, Triple<String, String, LinkedHashSet<String>>>()
        for (r in resolved) {
            val rel = r["rel"] as String
            val fqn = r["targetFqn"] as? String
            if (rel.startsWith("calls ") && !fqn.isNullOrEmpty()) {
                val mname = rel.substring(6).trim().trimEnd('(', ')')
                val mid = "method:$fqn#$mname"
                val info = methodsCalled.getOrPut(mid) { Triple(fqn, mname, LinkedHashSet()) }
                kn(r["from"])?.let { info.third.add(it) }
            }
        }
        for ((mid, info) in methodsCalled) {
            val (fqn, mname, callers) = info
            val cls = "java:$fqn"
            val label = fqn.substringAfterLast(".") + "." + mname + "()"
            addNode(
                "method", mid.substringAfter(":"), label, null,
                linkedMapOf("name" to mname, "class" to fqn, "declaredIn" to (if (cls in nodes) cls else null)),
            )
            for (c in callers) addEdge(c, mid, "calls")
            if (cls in nodes) addEdge(mid, cls, "declared-in")
        }

        // expression --calls--> method / java class
        val beanFqn = LinkedHashMap<String, String>()
        for (r in resolved) {
            val fqn = r["targetFqn"] as? String
            if (r["kind"] == "bean" && !fqn.isNullOrEmpty()) beanFqn[r["value"] as String] = fqn
        }
        for (expr in ctx.exprUse.keys) {
            val enode = "expression:$expr"
            if (enode !in nodes) continue
            val body = EXPR_STRIP_RE.replace(expr, "")
            for (cm in Constants.METHOD_CALL_FULL_RE.findAll(body)) {
                val fqn = beanFqn[cm.groupValues[1]] ?: continue
                val mid = "method:$fqn#${cm.groupValues[2]}"
                addEdge(enode, if (mid in nodes) mid else "java:$fqn", "calls")
            }
        }

        // model -> external (unresolved beans/classes/platform + missing model keys)
        val extSeen = HashSet<String>()
        for (r in (result["unresolvedRefs"] as? List<Map<String, Any?>> ?: emptyList())) {
            val kind = r["kind"] as String
            // named correlation nodes: signal/message/error/escalation/topic — one node per name,
            // throwers and catchers (or workers) connect to it from both sides
            if (kind in NAMED_REF_KINDS) {
                val value = r["value"] as String
                val nid = "$kind:$value"
                if (nid !in nodes) {
                    nodes[nid] = linkedMapOf(
                        "id" to nid, "type" to kind, "label" to value, "key" to value,
                        "file" to null, "data" to linkedMapOf<String, Any?>(),
                    )
                }
                addEdge(kn(r["from"]), nid, r["rel"] as String, suspect = r["suspect"] == true)
                continue
            }
            val data: LinkedHashMap<String, Any?> = when {
                kind == "bean" || kind == "class" -> {
                    val platform = kind == "bean" && r["value"] in FLOWABLE_PLATFORM_BEANS
                    linkedMapOf("platform" to platform, "kind" to kind)
                }
                r["targetType"] == "model" -> linkedMapOf("kind" to kind, "missingModel" to true)
                else -> {
                    // No node type exists for this ref kind — surface it instead of silently
                    // dropping (a newly added non-model ref kind would otherwise just vanish).
                    (result["diagnostics"] as? MutableList<Any?>)?.add(linkedMapOf(
                        "kind" to "unresolved-ref", "path" to (r["fromFile"] ?: ""),
                        "message" to "unhandled unresolved ref kind '$kind' (${r["rel"]} -> ${r["value"]})",
                    ))
                    continue
                }
            }
            val value = r["value"] as String
            val nid = "external:$value"
            if (nid !in extSeen) {
                extSeen.add(nid)
                nodes[nid] = linkedMapOf(
                    "id" to nid, "type" to "external", "label" to value, "key" to value, "file" to null, "data" to data,
                )
            }
            addEdge(kn(r["from"]), nid, r["rel"] as String, suspect = r["suspect"] == true)
        }

        // dynamic (expression-valued) references — best-effort resolved by the reference resolver
        // (constant-backed `${ident}` → model), else an expression placeholder node. Both variants
        // carry `dynamic=true` so the explorer renders them dashed and they stay filterable.
        for (r in (result["dynamicRefs"] as? List<Map<String, Any?>> ?: emptyList())) {
            val s = kn(r["from"]) ?: continue
            val rel = r["rel"] as? String ?: continue
            val kind = r["kind"] as? String ?: continue
            val resolvedNode = (r["resolvedValue"] as? String)?.let { kn(it) }
            if (resolvedNode != null) {
                addEdge(s, resolvedNode, rel, dynamic = true)
                continue
            }
            if (!(kind.startsWith("model:") || kind in ReferenceResolver.MODEL_KIND_NAMES)) continue
            val value = r["value"] as? String ?: continue
            val nid = "external:$value"
            if (nid !in extSeen && nid !in nodes) {
                extSeen.add(nid)
                nodes[nid] = linkedMapOf(
                    "id" to nid, "type" to "external", "label" to value, "key" to value, "file" to null,
                    "data" to linkedMapOf<String, Any?>("dynamic" to true, "kind" to kind),
                )
            }
            addEdge(s, nid, rel, dynamic = true)
        }

        // rest calls -> endpoint (matched) or external url
        for (rc in ctx.restCalls) {
            val s = kn(rc["source"])
            val matchEps = rc["_matchEps"] as? List<Map<String, Any?>>
            if (!matchEps.isNullOrEmpty()) {
                for (ep in matchEps) {
                    addEdge(s, "endpoint:${ep["http"]} ${ep["path"]}", "rest-call", suspect = ep["loose"] == true)
                }
                continue
            }
            val url = rc["url"] as? String ?: continue
            if (DATAOBJ_QUERY_RE.containsMatchIn(url)) continue
            val data = linkedMapOf<String, Any?>("method" to rc["method"])
            val rel: String
            if (url.contains("#/") || url.trimStart().startsWith("#")) {
                data["route"] = true; rel = "navigates-to"
            } else if (ENDPOINTS_PLATFORM_RE.containsMatchIn(url)) {
                data["platform"] = true; data["flowableApi"] = true; rel = "rest-call"
            } else {
                data["external_url"] = true; rel = "rest-call"
            }
            val nid = "external:$url"
            if (nid !in extSeen) {
                extSeen.add(nid)
                nodes[nid] = linkedMapOf(
                    "id" to nid, "type" to "external", "label" to url, "key" to url, "file" to null, "data" to data,
                )
            }
            addEdge(s, nid, rel)
        }

        // group -> model (access)
        for (a in ctx.access) {
            val t = kn(a["model"])
            for (g in (a["groups"] as List<String>)) {
                if (g.contains("\${") || g.contains("{{")) continue
                addEdge("group:$g", t, a["action"] as String)
            }
        }

        // controller -> endpoint it serves
        for (o in bucketList("endpoints")) {
            val ep = o as Map<String, Any?>
            if (truthy(ep["controllerFqn"])) {
                addEdge("java:${ep["controllerFqn"]}", "endpoint:${ep["http"]} ${ep["path"]}", "serves")
            }
        }

        // java -> java dependency wiring + CODE -> MODEL string-literal references
        for ((fqn, jc) in allJava) {
            val snode = "java:$fqn"
            if (snode !in nodes) continue
            for (dep in (jc["deps"] as? Collection<String> ?: emptyList())) {
                val dfqn = simpleToFqn[dep]
                if (dfqn != null && dfqn != fqn && "java:$dfqn" in nodes) {
                    addEdge(snode, "java:$dfqn", "uses", suspect = dep in ambiguousSimple)
                }
            }
            // a literal passed to a known key-taking API is a confident reference; any other
            // literal that merely equals a model key ("status", "customer", …) only a suspect one
            val keyed = jc["keyedStrings"] as? Collection<*> ?: emptyList<Any?>()
            for (s in (jc["strings"] as? Collection<*> ?: emptyList<Any?>())) {
                if (s !in modelKeys) continue
                val t = kn(s)
                if (t != null && t.substringBefore(":") !in setOf("liquibase", "java", "endpoint", "group")) {
                    addEdge(snode, t, "references", suspect = s !in keyed)
                }
            }
            // external-worker subscriptions: the class polls a topic — meets the BPMN/CMMN
            // `external-topic` refs in the shared topic node
            for (t in (jc["topics"] as? Collection<*> ?: emptyList<Any?>())) {
                val tid = "topic:$t"
                if (tid !in nodes) {
                    nodes[tid] = linkedMapOf(
                        "id" to tid, "type" to "topic", "label" to t, "key" to t,
                        "file" to null, "data" to linkedMapOf<String, Any?>(),
                    )
                }
                addEdge(snode, tid, "worker-topic")
            }
        }

        // query -> group
        for (n in nodes.values.toList()) {
            if (n["type"] != "query") continue
            val data = n["data"] as MutableMap<String, Any?>
            val qm = ctx.queryMeta[n["key"]]
            if (qm != null) {
                data["groups"] = (qm["groups"] as Collection<String>).sorted()
                data["sourceIndex"] = qm["sourceIndex"].takeIf { truthy(it) } ?: data["sourceIndex"]
            }
            for (grp in (data["groups"] as? List<String> ?: emptyList())) {
                val gid = "group:$grp"
                if (gid in nodes) addEdge(n["id"] as String, gid, "filters-by-group")
            }
        }

        // action -> bot (botKey)
        val botToFqn = LinkedHashMap<String, String>()
        for (j in allJava.values) {
            val bk = j["botKey"]
            if (truthy(bk)) botToFqn[bk.toString()] = j["fqn"] as String
        }
        for (o in bucketList("actions")) {
            val a = o as Map<String, Any?>
            val bot = a["botKey"]
            if (!truthy(bot)) continue
            val botStr = bot.toString()
            val anode = kn(a["key"])
            val botNode = kn(botStr)
            val jc = allJava.values.firstOrNull { j ->
                botStr in (j["beanNames"] as Collection<String>) || j["primary"] == botStr
            }
            when {
                botNode != null -> addEdge(anode, botNode, "bot")
                botStr in botToFqn -> addEdge(anode, "java:${botToFqn[botStr]}", "bot")
                jc != null -> addEdge(anode, "java:${jc["fqn"]}", "bot")
                else -> {
                    val bid = "bot:$botStr"
                    if (bid !in nodes) {
                        nodes[bid] = linkedMapOf(
                            "id" to bid, "type" to "bot", "label" to botStr, "key" to botStr,
                            "file" to null, "data" to linkedMapOf<String, Any?>("platform" to true),
                        )
                    }
                    addEdge(anode, bid, "bot")
                }
            }
        }

        // service / data object -> liquibase changelog (authoritative signals only)
        val lbByKey = LinkedHashMap<String, String>()
        val lbByTable = LinkedHashMap<String, LinkedHashSet<String>>()
        val lbBySvcref = LinkedHashMap<String, LinkedHashSet<String>>()
        for (o in bucketList("liquibase")) {
            val lb = o as Map<String, Any?>
            val lid = "liquibase:${lb["key"]}"
            lbByKey[lb["key"] as String] = lid
            val tables = LinkedHashSet<String>()
            (lb["effectiveTables"] as? Collection<String>)?.let { tables.addAll(it) }
            (lb["tables"] as? Collection<String>)?.let { tables.addAll(it) }
            for (t in tables) lbByTable.getOrPut(t.uppercase()) { LinkedHashSet() }.add(lid)
            for (sk in (lb["serviceRefs"] as? Collection<String> ?: emptyList())) {
                lbBySvcref.getOrPut(sk) { LinkedHashSet() }.add(lid)
            }
        }
        for (n in nodes.values) {
            when (n["type"]) {
                "service" -> {
                    val data = n["data"] as Map<String, Any?>
                    val rk = data["referencedLiquibaseModelKey"]
                    if (truthy(rk) && rk.toString() in lbByKey) addEdge(n["id"] as String, lbByKey[rk.toString()], "schema")
                    for (lid in (lbBySvcref[n["key"]] ?: emptySet())) addEdge(n["id"] as String, lid, "schema")
                    val tn = data["tableName"]
                    if (truthy(tn)) for (lid in (lbByTable[tn.toString().uppercase()] ?: emptySet())) {
                        addEdge(n["id"] as String, lid, "schema")
                    }
                }
                "dataObject" -> {
                    val key = n["key"].toString()
                    for (cand in listOf(key, key + "Schema")) {
                        if (cand in lbByKey) addEdge(n["id"] as String, lbByKey[cand], "schema")
                    }
                }
            }
        }

        // app -> model membership (co-located models)
        val appByContainer = LinkedHashMap<String, String?>()
        for (o in bucketList("apps")) {
            val am = o as Map<String, Any?>
            val c = containerOf(am["file"] as? String)
            if (c != null) appByContainer[c] = kn(am["key"])
        }
        if (appByContainer.isNotEmpty()) {
            val nonModel = setOf(
                "java", "endpoint", "group", "external", "bot", "liquibase", "app",
                "expression", "binding", "variable", "string", "method",
                // named correlation nodes share their key with real models (a signal named after
                // the process it starts) — app membership must never attach to them
            ) + NAMED_REF_KINDS
            for (n in nodes.values.toList()) {
                if (n["type"] in nonModel) continue
                val key = n["key"]
                val containers = LinkedHashSet<String?>()
                for ((_, f) in (byKey[key] ?: emptyList())) containers.add(containerOf(f))
                containers.add(containerOf(n["file"] as? String))
                for (c in containers) {
                    if (c != null && c in appByContainer) addEdge(appByContainer[c], n["id"] as String, "contains")
                }
            }
        }

        // drop _matchEps from restCalls before serialising (internal only)
        for (rc in ctx.restCalls) rc.remove("_matchEps")

        // dedupe edges — when the same (s,t,rel) exists both flagged and unflagged, the strongest
        // signal wins: a clean occurrence clears `suspect`/`dynamic` from the kept edge.
        val edgeByKey = LinkedHashMap<List<Any?>, LinkedHashMap<String, Any?>>()
        for (e in edges) {
            val k = listOf(e["s"], e["t"], e["rel"])
            val prev = edgeByKey[k]
            if (prev == null) {
                edgeByKey[k] = e
            } else {
                if (prev["suspect"] == true && e["suspect"] != true) prev.remove("suspect")
                if (prev["dynamic"] == true && e["dynamic"] != true) prev.remove("dynamic")
            }
        }
        val uniq = ArrayList<Map<String, Any?>>(edgeByKey.values)

        val nodeList = ArrayList<Any?>(nodes.values)
        result["graph"] = linkedMapOf("nodes" to nodeList, "edges" to uniq)
        result["stats"] = linkedMapOf(
            "models" to ctx.modelFileCount,
            "archives" to ctx.archiveFileCount,
            "java" to ctx.javaFileCount,
            "endpoints" to bucketList("endpoints").size,
            "groups" to ctx.groups.size,
            "nodes" to nodeList.size,
            "edges" to uniq.size,
            "suspectEdges" to uniq.count { it["suspect"] == true },
            "dynamicEdges" to uniq.count { it["dynamic"] == true },
        )
    }

    // ---- helpers ----

    /** Python truthiness for `.get(...) or …` / `if x:` idioms (None/""/0/empty → false). */
    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is String -> v.isNotEmpty()
        is Number -> v.toDouble() != 0.0
        is Collection<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        else -> true
    }

    /** `os.path.basename` for the '/'-separated rel paths Atlas uses. */
    private fun basename(path: String?): String? {
        if (path == null) return null
        return if ("/" in path) path.substringAfterLast("/") else path
    }

    /** The 'app container' of a file: its archive (before '!') or its parent dir. */
    private fun containerOf(path: String?): String? {
        if (path.isNullOrEmpty()) return null
        if ("!" in path) return path.substringBefore("!")
        return if ("/" in path) path.substringBeforeLast("/") else "."
    }

    /** Variable identifiers used in a `${…}`/`#{…}` expression (not beans/functions/context). */
    private fun varsInExpr(expr: String): Set<String> {
        var body = EXPR_STRIP_RE.replace(expr, "")
        body = STR_IN_EXPR_RE.replace(body, " ")
        val beans = Constants.METHOD_CALL_FULL_RE.findAll(body).map { it.groupValues[1] }.toSet()
        val out = LinkedHashSet<String>()
        for (m in ROOT_IDENT_RE.findAll(body)) {
            val n = m.groupValues[1]
            val after = body.substring(m.range.last + 1).trimStart()
            if (after.isNotEmpty() && after[0] == '(') continue
            if (n in Constants.FLOWABLE_CONTEXT || n in Constants.JAVA_LITERALS || n in beans) continue
            out.add(n)
        }
        return out
    }

    /** The bound variable name of a `{{…}}` binding, e.g. `{{order.x}}` -> `order`. */
    private fun varInMustache(ph: String): String? {
        val inner = ph.trim('{', '}', ' ').trim()
        val m = MUSTACHE_HEAD_RE.find(inner) ?: return null
        val root = m.groupValues[1]
        if (root in MUSTACHE_IGNORE || root.trimStart('$') in MUSTACHE_IGNORE) return null
        return root
    }

    /**
     * Validate a harvested expression/binding node. Returns problem dicts (empty == valid) or null
     * when the harvester likely truncated the body (a stray `{` in the inner text). Mirrors
     * `validate_harvested_expr`: decode XML entities then JSON backslash escapes, guard truncation,
     * validate the (still-wrapped) body and attach a `snippet`.
     */
    private fun validateHarvestedExpr(
        text: String, dialect: ExpressionDialect, custom: CustomFunctionCatalog?,
    ): List<Map<String, Any?>>? {
        val body = decodeJsonStringEscapes(Constants.htmlUnescape(text))
        val (inner, _) = ExprWrappers.stripOuter(body)
        if (inner.contains("{")) return null
        val problems = ExpressionValidator.validate(body, dialect, custom)
        return problems.map { p ->
            val snippet = if (p.startOffset in 0 until p.endOffset && p.endOffset <= body.length) {
                body.substring(p.startOffset, p.endOffset)
            } else ""
            problemToDict(p, snippet)
        }
    }

    /** Map the Kotlin [ExprProblem] to the Python `validate_expression` problem-dict shape. */
    private fun problemToDict(p: ExprProblem, snippet: String): Map<String, Any?> {
        val d = linkedMapOf<String, Any?>(
            "start" to p.startOffset,
            "end" to p.endOffset,
            "message" to p.message,
            "severity" to if (p.severity == ExprSeverity.ERROR) "error" else "warning",
            "quickFix" to p.quickFix,
        )
        val ks = kindString(p.kind)
        if (ks != null) {
            d["kind"] = ks
            d["subject"] = p.subject
        }
        d["snippet"] = snippet
        return d
    }

    /** Python `_problem`'s `kind` string; null for kinds Python passes as `kind=None` (no kind/subject). */
    private fun kindString(kind: ExprProblemKind): String? = when (kind) {
        ExprProblemKind.UNKNOWN_NAMESPACE -> "unknown-namespace"
        ExprProblemKind.UNKNOWN_FUNCTION -> "unknown-function"
        ExprProblemKind.UNKNOWN_ROOT -> "unknown-root"
        ExprProblemKind.SYNTAX, ExprProblemKind.DIALECT_MISUSE -> null
    }

    private fun exprProblemAllowlisted(p: Map<String, Any?>, allow: Set<String>): Boolean {
        val subj = p["subject"] as? String
        if (subj.isNullOrEmpty() || p["severity"] == "error") return false
        if (subj in allow) return true
        for (sep in listOf(":", ".")) {
            val idx = subj.indexOf(sep)
            if (idx >= 0 && subj.substring(0, idx) in allow) return true
        }
        return false
    }

    private fun decodeJsonStringEscapes(s: String): String {
        if (!s.contains('\\')) return s
        val out = StringBuilder(s.length)
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == '\\' && i + 1 < n) {
                val nx = s[i + 1]
                out.append(
                    when (nx) {
                        '"' -> '"'; '\\' -> '\\'; '/' -> '/'; 'n' -> '\n'; 't' -> '\t'
                        'r' -> '\r'; 'b' -> '\b'; 'f' -> ''; else -> nx
                    },
                )
                i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    /** Display names of custom functions called in a frontend binding body (port of `custom_fns_called_in`). */
    private fun customFnsCalledIn(text: String, cat: CustomFunctionCatalog): Set<String> {
        val body = decodeJsonStringEscapes(Constants.htmlUnescape(text))
        val found = LinkedHashSet<String>()
        for ((ns, members) in cat.namespaces) {
            for (m in members) {
                if (Regex("\\b" + Regex.escape(ns) + "\\s*\\.\\s*" + Regex.escape(m) + "\\b").containsMatchIn(body)) {
                    found.add("$ns.$m")
                }
            }
        }
        for (m in cat.flw) {
            if (Regex("\\bflw\\s*\\.\\s*" + Regex.escape(m) + "\\b").containsMatchIn(body)) found.add("flw.$m")
        }
        for (fn in cat.topLevel) {
            if (Regex("\\b" + Regex.escape(fn) + "\\s*\\(").containsMatchIn(body)) found.add(fn)
        }
        return found
    }

    /** Flatten a custom-function catalog into (display, kind, namespace, member) tuples (sorted). */
    private fun customFunctionEntries(cat: CustomFunctionCatalog): List<CustomEntry> {
        val out = ArrayList<CustomEntry>()
        for (ns in cat.namespaces.keys.sorted()) {
            for (m in (cat.namespaces.getValue(ns)).sorted()) out.add(CustomEntry("$ns.$m", "namespace", ns, m))
        }
        for (m in cat.flw.sorted()) out.add(CustomEntry("flw.$m", "flw", "flw", m))
        for (fn in cat.topLevel.sorted()) out.add(CustomEntry(fn, "top-level", null, fn))
        return out
    }

    private data class CustomEntry(val display: String, val kind: String, val namespace: String?, val member: String)
}
