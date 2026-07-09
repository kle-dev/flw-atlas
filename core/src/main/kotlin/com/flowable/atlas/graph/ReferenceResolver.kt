package com.flowable.atlas.graph

import com.flowable.atlas.parsing.Constants
import com.flowable.atlas.parsing.JavaParser
import com.flowable.atlas.parsing.ModelKinds
import java.io.File

/**
 * The reference-resolution tail of extraction — a faithful port of `flowable_atlas.py` `extract`
 * (~lines 1388-1608) covering everything *after* the model/archive dispatch loops and *before*
 * `_build_graph` / the data-object & schema enrichment steps:
 *
 *  - the Java-source loop (bean/class/fqn indexes via [JavaParser.parseJava], endpoints, controllers,
 *    glue classes, `javaByRole`);
 *  - de-duplication of refs / dynamic refs / rest-calls / model buckets / access entries;
 *  - platform query-URL model references;
 *  - static reference resolution (`ctx.refs` -> `resolvedRefs` / `unresolvedRefs` against the model
 *    index, `by_key` fallback and the Java indexes);
 *  - REST-call matching to code endpoints;
 *  - variable / bean / expression harvesting;
 *  - the result-section assembly (modelIndex string form, restCalls, beanIndex, variables, beans,
 *    expressions, placeholders, delegateClasses, access, groups, javaByRole, dynamicRefs).
 *
 * Liquibase coverage, `_build_graph`/`_uses` and custom-function discovery are separate increments and
 * are NOT done here; `customFunctions` is set to `null`.
 */
object ReferenceResolver {

    /**
     * The internal resolution structures the graph builder (`_build_graph`) consumes — kept separate
     * from the cleaned-up sections written into `result` (Python passes these directly as arguments):
     *  - [resolved]: the FULL resolved-refs list (each entry carries `targetFqn` for bean/class refs);
     *  - [allJava]: fqn → parsed-java object for every `.java` (Python's `all_java`);
     *  - [beanMethods]: bean name → set of method names called on it in expressions (Python's `bean_methods`).
     */
    data class Resolved(
        val resolved: List<Map<String, Any?>>,
        val allJava: Map<String, Map<String, Any?>>,
        val beanMethods: Map<String, Set<String>>,
    )

    /** Ref kinds that name a Flowable model (mirrors the tuple in the Python `resolve references` step). */
    private val MODEL_KIND_NAMES = setOf(
        "process", "case", "decision", "form", "page",
        "service", "agent", "dataObject", "dataDictionary",
        "channel", "event", "template", "sla", "securityPolicy",
        "knowledgeBase", "query", "sequence", "masterData",
        "action", "document", "variableExtractor", "dashboardComponent",
    )

    private val EXPR_STRIP_RE = Regex("^[#$]\\{|\\}$")
    private val STR_LIT_IN_EXPR_RE = Regex("'[^']*'|\"[^\"]*\"")
    private val ROOT_IDENT_RE = Regex("(?<![\\w.\$])([A-Za-z_]\\w*)")
    private val MUSTACHE_HEAD_RE = Regex("^\\$?([A-Za-z_]\\w*)")
    private val DATA_OBJ_KEY_RE = Regex("dataObjectDefinitionKey=([A-Za-z0-9_.\\-]+)")
    private val QUERY_PATH_RE = Regex("/query/([A-Za-z0-9_.\\-]+)")

    /** Root identifiers a `{{…}}` placeholder may carry that are never project variables. */
    private val MUSTACHE_IGNORE = setOf(
        "endpoints", "item", "index", "ctx", "root", "parent", "event", "self",
        "first", "last", "start", "pageSize", "flw", "payload", "temp", "filter",
        "sortColumn", "sortDirection", "orderBy", "sortBy", "total", "response",
        "page", "size", "data", "value", "params",
    )

    /**
     * Run the full tail. Mutates [result] and [ctx] in place. [relOf] turns a discovered file into the
     * label used across the result (relative path for a directory root, base name for a single archive);
     * [diag] records a `(kind, path, message)` diagnostic (+ legacy warning).
     */
    @Suppress("UNCHECKED_CAST")
    fun resolve(
        result: LinkedHashMap<String, Any?>,
        ctx: Ctx,
        modelIndex: LinkedHashMap<Pair<String, String>, String>,
        byKey: LinkedHashMap<String, MutableList<Pair<String, String>>>,
        javas: List<File>,
        relOf: (File) -> String,
        diag: (String, String, String) -> Unit,
    ): Resolved {
        fun bucketList(name: String) = result[name] as ArrayList<Any?>

        // ---- Java ----
        val beanIndex = LinkedHashMap<String, Map<String, Any?>>()
        val classIndex = LinkedHashMap<String, Map<String, Any?>>()
        val fqnIndex = LinkedHashMap<String, Map<String, Any?>>()
        val javaByRole = LinkedHashMap<String, ArrayList<Any?>>()

        for (path in javas) {
            val rel = relOf(path)
            val jc: Map<String, Any?> = try {
                JavaParser.parseJava(String(path.readBytes(), Charsets.UTF_8), rel)
            } catch (e: Exception) {
                diag("java", rel, e.message ?: e.toString())
                continue
            }
            val primary = jc["primary"] as String
            val fqn = jc["fqn"] as String
            for (b in jc["beanNames"] as Collection<String>) beanIndex.putIfAbsent(b, jc)
            classIndex.putIfAbsent(primary, jc)
            fqnIndex[fqn] = jc
            for (ep in jc["endpoints"] as List<Map<String, Any?>>) {
                val ep2 = LinkedHashMap(ep)
                ep2["file"] = rel
                ep2["controller"] = primary
                ep2["controllerFqn"] = fqn
                bucketList("endpoints").add(ep2)
            }
            if (jc["isController"] as Boolean) bucketList("javaControllers").add(jc)
            if (jc["isGlue"] as Boolean) bucketList("javaGlue").add(jc)
            for (role in jc["roles"] as Collection<String>) {
                javaByRole.getOrPut(role) { ArrayList() }.add(jc)
            }
        }

        // ---- Dedupe (the same model is often present both loose and inside a -bar.zip) ----
        fun <T> dedupe(items: List<T>, keyfn: (T) -> Any?): ArrayList<T> {
            val seen = HashSet<Any?>()
            val out = ArrayList<T>()
            for (it in items) if (seen.add(keyfn(it))) out.add(it)
            return out
        }
        fun refKey(r: Map<String, Any?>) = listOf(r["from"], r["rel"], r["kind"], r["value"])

        replaceInPlace(ctx.refs, dedupe(ctx.refs, ::refKey))
        replaceInPlace(ctx.dynamicRefs, dedupe(ctx.dynamicRefs, ::refKey))
        replaceInPlace(ctx.restCalls, dedupe(ctx.restCalls) { rc -> listOf(rc["source"], rc["where"], rc["url"]) })
        for (bucket in ModelKinds.MODEL_BUCKETS) {
            val cur = bucketList(bucket)
            replaceInPlace(cur, dedupe(cur) { o ->
                val k = (o as? Map<*, *>)?.get("key")
                if (k is String && k.isNotEmpty()) k else Any()
            })
        }
        replaceInPlace(ctx.access, dedupe(ctx.access) { a ->
            listOf(a["model"], a["scope"], a["action"], a["groups"], a["users"])
        })

        // ---- Platform query URLs carry model references in their query string / path ----
        for (rc in ctx.restCalls) {
            val url = (rc["url"] as? String) ?: ""
            val source = rc["source"]
            val sourceFile = (rc["sourceFile"] as? String) ?: ""
            for (m in DATA_OBJ_KEY_RE.findAll(url)) {
                ctx.addRef(source, "form", sourceFile, "queries-dataObject", "dataObject", m.groupValues[1])
            }
            for (m in QUERY_PATH_RE.findAll(url)) {
                ctx.addRef(source, "form", sourceFile, "runs-query", "query", m.groupValues[1])
            }
        }
        replaceInPlace(ctx.refs, dedupe(ctx.refs, ::refKey))

        // ---- Resolve references ----
        val resolved = ArrayList<Map<String, Any?>>()
        val unresolved = ArrayList<Map<String, Any?>>()
        for (ref in ctx.refs) {
            val kind = ref["kind"] as String
            val value = ref["value"] as String
            var target: String? = null
            val ref2 = LinkedHashMap(ref)
            when {
                kind.startsWith("model:") || kind in MODEL_KIND_NAMES -> {
                    var norm = if (kind.startsWith("model:")) kind.substringAfter(":") else kind
                    norm = ModelKinds.NORMALIZE_TYPE[norm] ?: norm
                    target = modelIndex[norm to value]
                    ref2["target"] = target
                    ref2["targetType"] = "model"
                    if (target == null && value in byKey) {
                        // Fallback across model types: prefer a same-type entry, else the first,
                        // tagging cross-type resolutions so they stay auditable.
                        val entries = byKey[value]!!
                        val match = entries.firstOrNull { it.first == norm } ?: entries[0]
                        target = match.second
                        ref2["target"] = target
                        if (match.first != norm) ref2["fallbackType"] = match.first
                    }
                }
                kind == "bean" -> {
                    val cap = if (value.isNotEmpty()) value[0].uppercaseChar() + value.substring(1) else value
                    val jc = beanIndex[value] ?: classIndex[cap]
                    target = if (jc != null) "${jc["file"]}:${jc["line"]} (${jc["fqn"]})" else null
                    ref2["target"] = target
                    ref2["targetType"] = "bean"
                    ref2["targetFqn"] = jc?.get("fqn")
                }
                kind == "class" -> {
                    val simple = value.substringAfterLast('.')
                    val jc = fqnIndex[value] ?: classIndex[simple]
                    target = if (jc != null) "${jc["file"]}:${jc["line"]}" else null
                    ref2["target"] = target
                    ref2["targetType"] = "class"
                    ref2["targetFqn"] = jc?.get("fqn")
                }
                else -> {
                    ref2["target"] = null
                    ref2["targetType"] = kind
                }
            }
            (if (target != null) resolved else unresolved).add(ref2)
        }
        // Must land in result BEFORE the graph step — it reads unresolvedRefs.
        result["resolvedRefs"] = resolved
        result["unresolvedRefs"] = unresolved
        result["dynamicRefs"] = ctx.dynamicRefs

        // ---- Resolve REST calls -> code endpoints ----
        val codeEndpoints = bucketList("endpoints") as List<Map<String, Any?>>
        for (rc in ctx.restCalls) {
            val matchEps = JavaParser.matchRest(rc["url"] as? String, codeEndpoints)
            rc["_matchEps"] = matchEps
            rc["matches"] = matchEps.map { m ->
                "${m["http"]} ${m["path"]} -> ${m["controller"]}#${m["handler"]} (${m["file"]}:${m["line"]})"
            }
        }

        // ---- Variables / beans / expressions (+ bean.method() map for the graph) ----
        val beans = LinkedHashSet<String>()
        val variables = LinkedHashSet<String>()
        val beanMethods = LinkedHashMap<String, MutableSet<String>>()
        for (e in ctx.expr) {
            var body = EXPR_STRIP_RE.replace(e, "")
            body = STR_LIT_IN_EXPR_RE.replace(body, " ")   // drop string literals
            for (bm in Constants.METHOD_CALL_FULL_RE.findAll(body)) {
                val b = bm.groupValues[1]
                if (b !in Constants.FLOWABLE_CONTEXT && b !in Constants.JAVA_LITERALS) {
                    beans.add(b)
                    beanMethods.getOrPut(b) { LinkedHashSet() }.add(bm.groupValues[2])
                }
            }
            for (im in ROOT_IDENT_RE.findAll(body)) {
                val n = im.groupValues[1]
                val after = body.substring(im.range.last + 1).trimStart()
                if ((after.isNotEmpty() && after[0] == '(') ||
                    n in Constants.FLOWABLE_CONTEXT || n in Constants.JAVA_LITERALS
                ) continue
                variables.add(n)
            }
        }
        for (ph in ctx.mustache) {
            val m = MUSTACHE_HEAD_RE.find(ph.trim('{', '}', ' ').trim())
            if (m != null && m.groupValues[1] !in MUSTACHE_IGNORE) variables.add(m.groupValues[1])
        }

        // ---- Result-section assembly ----
        val modelIndexStr = LinkedHashMap<String, Any?>()
        for ((k, v) in modelIndex) modelIndexStr["${k.first}:${k.second}"] = v
        result["modelIndex"] = modelIndexStr
        result["restCalls"] = ctx.restCalls
        result["beanIndex"] = beanIndex.keys.sorted()
        result["variables"] = (variables - beans).sorted()
        result["beans"] = beans.sorted()
        result["expressions"] = ctx.expr.sorted()
        result["placeholders"] = ctx.mustache.sorted()
        result["delegateClasses"] = ctx.delegateClasses.sorted()
        result["access"] = ctx.access
        result["groups"] = ctx.groups.sorted()
        result["javaByRole"] = javaByRole
        result["customFunctions"] = null

        // `fqnIndex` is Python's `all_java` (both are `map[jc.fqn] = jc`, same loop, last wins).
        return Resolved(resolved, fqnIndex, beanMethods)
    }

    /** Replace the contents of [dst] with [src] in place (the Python `ctx[..] = _dedupe(..)` idiom). */
    private fun <T> replaceInPlace(dst: MutableList<T>, src: List<T>) {
        dst.clear()
        dst.addAll(src)
    }
}
