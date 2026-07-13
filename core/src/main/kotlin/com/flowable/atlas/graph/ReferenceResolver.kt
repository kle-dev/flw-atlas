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
    internal val MODEL_KIND_NAMES = setOf(
        "process", "case", "decision", "form", "page",
        "service", "agent", "dataObject", "dataDictionary",
        "channel", "event", "template", "sla", "securityPolicy",
        "knowledgeBase", "query", "sequence", "masterData",
        "action", "document", "variableExtractor", "dashboardComponent",
    )

    /**
     * Cross-type fallback compatibility: an expected model kind may silently fall back only to these
     * types (tagged `fallbackType`, still a normal edge). Any other cross-type match is kept but
     * flagged `suspect` — a `process --callActivity--> form` edge is semantically impossible and must
     * be visible as such instead of looking like a clean resolution.
     */
    private val FALLBACK_COMPAT = mapOf(
        "process" to setOf("case"),          // call activity may start a case (calledElementType)
        "case" to setOf("process"),          // work definitions cover both
        "form" to setOf("page"),             // casePage-/work-form keys may name a page model
        "page" to setOf("form"),
        "dataObject" to setOf("masterData"), // master data is a data-object specialisation
        "masterData" to setOf("dataObject"),
    )

    /** A value that is exactly one `${ident}` / `#{ident}` — the only shape we best-effort resolve. */
    private val SIMPLE_EXPR_RE = Regex("^[#$]\\{\\s*([A-Za-z_]\\w*)\\s*}$")

    private val EXPR_STRIP_RE = Regex("^[#$]\\{|\\}$")
    private val STR_LIT_IN_EXPR_RE = Regex("'[^']*'|\"[^\"]*\"")
    private val ROOT_IDENT_RE = Regex("(?<![\\w.\$])([A-Za-z_]\\w*)")
    private val MUSTACHE_HEAD_RE = Regex("^\\$?([A-Za-z_]\\w*)")
    private val DATA_OBJ_KEY_RE = Regex("dataObjectDefinitionKey=([A-Za-z0-9_.\\-]+)")
    private val QUERY_PATH_RE = Regex("/query/([A-Za-z0-9_.\\-]+)")
    private val IDENT_RE = Regex("[A-Za-z_]\\w*")

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

        // Global `static final String` constants (simple name → value) and per-class data-object
        // operation calls, collected during the java pass and resolved into op-uses just below.
        val javaConstants = LinkedHashMap<String, String>()
        val javaOpCalls = LinkedHashMap<String, List<Map<String, String>>>()
        // Simple class names shared by more than one class — resolving through them is a guess
        // (first-wins), so any ref that falls back to such a name is flagged `suspect`.
        val ambiguousSimple = HashSet<String>()
        for (path in javas) {
            val rel = relOf(path)
            val srcText = String(path.readBytes(), Charsets.UTF_8)
            val jc: Map<String, Any?> = try {
                JavaParser.parseJava(srcText, rel)
            } catch (e: Exception) {
                diag("java", rel, e.message ?: e.toString())
                continue
            }
            val primary = jc["primary"] as String
            val fqn = jc["fqn"] as String
            for (b in jc["beanNames"] as Collection<String>) beanIndex.putIfAbsent(b, jc)
            if (classIndex.putIfAbsent(primary, jc) != null) ambiguousSimple.add(primary)
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
            for ((n, v) in JavaParser.stringConstants(srcText)) javaConstants.putIfAbsent(n, v)
            val ops = JavaParser.dataObjectOpCalls(srcText)
            if (ops.isNotEmpty()) javaOpCalls[fqn] = ops
        }

        // ---- Java data-object operation calls (dataObjectRuntimeService…definitionKey(key).operation("op")) ----
        // Resolve each call's definitionKey — a string literal or a `static final String` constant
        // reference (a generated model-keys class field) — to a model key, then record it as an op-use so
        // the Java class shows up in the operation's "Used by" list (data objects resolve to their
        // backing service in the graph builder, exactly like the form/page data-source usages).
        fun resolveDefKey(expr: String): String? {
            val e = expr.trim()
            if (e.length >= 2 && e.startsWith('"') && e.endsWith('"')) return e.substring(1, e.length - 1)
            val simple = e.substringAfterLast('.')
            return if (IDENT_RE.matches(simple)) javaConstants[simple] else null
        }
        for ((fqn, calls) in javaOpCalls) {
            for (call in calls) {
                val key = resolveDefKey(call["def"] ?: continue) ?: continue
                ctx.addOpUse(fqn, "dataObject", key, call["op"])
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
            // The originating model type follows the call's kind — an http-task URL comes from a
            // process, a service-op URL from a service model, everything else from a form/page.
            val srcType = when (rc["kind"]) {
                "service-op" -> "service"
                "http-task" -> "process"
                else -> "form"
            }
            for (m in DATA_OBJ_KEY_RE.findAll(url)) {
                ctx.addRef(source, srcType, sourceFile, "queries-dataObject", "dataObject", m.groupValues[1])
            }
            for (m in QUERY_PATH_RE.findAll(url)) {
                ctx.addRef(source, srcType, sourceFile, "runs-query", "query", m.groupValues[1])
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
                        // Fallback across model types: prefer a same-type entry, then a semantically
                        // compatible one ([FALLBACK_COMPAT]) — anything else stays resolvable but is
                        // flagged `suspect` so impossible edges are visible instead of looking clean.
                        val entries = byKey[value]!!
                        val compat = FALLBACK_COMPAT[norm] ?: emptySet()
                        val match = entries.firstOrNull { it.first == norm }
                            ?: entries.firstOrNull { it.first in compat }
                            ?: entries[0]
                        target = match.second
                        ref2["target"] = target
                        if (match.first != norm) {
                            ref2["fallbackType"] = match.first
                            if (match.first !in compat) ref2["suspect"] = true
                        }
                    }
                }
                kind == "bean" -> {
                    val cap = if (value.isNotEmpty()) value[0].uppercaseChar() + value.substring(1) else value
                    val jc = beanIndex[value] ?: classIndex[cap]
                    target = if (jc != null) "${jc["file"]}:${jc["line"]} (${jc["fqn"]})" else null
                    ref2["target"] = target
                    ref2["targetType"] = "bean"
                    ref2["targetFqn"] = jc?.get("fqn")
                    // Resolved through a shared simple name — first-wins guess, keep it flagged.
                    if (jc != null && beanIndex[value] == null && cap in ambiguousSimple) ref2["suspect"] = true
                }
                kind == "class" -> {
                    val simple = value.substringAfterLast('.')
                    val jc = fqnIndex[value] ?: classIndex[simple]
                    target = if (jc != null) "${jc["file"]}:${jc["line"]}" else null
                    ref2["target"] = target
                    ref2["targetType"] = "class"
                    ref2["targetFqn"] = jc?.get("fqn")
                    if (jc != null && fqnIndex[value] == null && simple in ambiguousSimple) ref2["suspect"] = true
                }
                else -> {
                    ref2["target"] = null
                    ref2["targetType"] = kind
                }
            }
            (if (target != null) resolved else unresolved).add(ref2)
        }

        // ---- Best-effort resolution of dynamic (expression-valued) references ----
        // A model ref whose value is exactly `${ident}` resolves when `ident` is a project constant
        // (`static final String`, e.g. a generated model-keys class) whose value is an indexed model
        // key. Everything else keeps its expression text; either way the graph builder renders these
        // as `dynamic` edges (resolved → the model, unresolved → an expression placeholder node).
        for (ref in ctx.dynamicRefs) {
            ref["dynamic"] = true
            val kind = ref["kind"] as? String ?: continue
            if (!(kind.startsWith("model:") || kind in MODEL_KIND_NAMES)) continue
            var norm = if (kind.startsWith("model:")) kind.substringAfter(":") else kind
            norm = ModelKinds.NORMALIZE_TYPE[norm] ?: norm
            val value = ref["value"] as? String ?: continue
            val ident = SIMPLE_EXPR_RE.matchEntire(value)?.groupValues?.get(1) ?: continue
            val candidate = javaConstants[ident] ?: continue
            val hit = modelIndex[norm to candidate] ?: continue
            ref["resolvedValue"] = candidate
            ref["target"] = hit
            ref["targetType"] = "model"
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
