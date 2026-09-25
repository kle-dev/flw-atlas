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
     *  - [beanMethods]: bean name → set of method names called on it in expressions (Python's `bean_methods`);
     *  - [knownBeans]: every name the project may treat as a bean — the platform's, Java's, the ones a
     *    delegate names bare, and the expression roots [isBean] accepted. A root outside it is a variable.
     */
    data class Resolved(
        val resolved: List<Map<String, Any?>>,
        val allJava: Map<String, Map<String, Any?>>,
        val beanMethods: Map<String, Set<String>>,
        val knownBeans: Set<String>,
    )

    /** A bean ref whose root may as well be a variable: a `${x.method()}` harvest, or an `expression` attribute. */
    private fun isCallRel(rel: String) = rel.startsWith("calls ") || rel.endsWith("-expression")

    /** Ref kinds that name a Flowable model (mirrors the tuple in the Python `resolve references` step). */
    internal val MODEL_KIND_NAMES = setOf(
        "process", "case", "decision", "form", "page",
        "service", "agent", "dataObject", "dataDictionary",
        "channel", "event", "template", "sla", "securityPolicy",
        "knowledgeBase", "query", "sequence", "masterData",
        "action", "document", "variableExtractor", "dashboardComponent",
    )

    /**
     * Cross-type fallback compatibility: an expected model kind may fall back only to these types (tagged
     * `fallbackType`, still a normal edge). Any other model of the same key is a different model: the
     * reference stays unresolved — a missing model — instead of drawing a `process --callActivity--> form`
     * edge that also hid the missing callee.
     */
    private val FALLBACK_COMPAT = mapOf(
        "form" to setOf("page"),             // casePage-/work-form keys may name a page model
        "page" to setOf("form"),
        "dataObject" to setOf("masterData"), // master data is a data-object specialisation
        "masterData" to setOf("dataObject"),
    )

    /**
     * The references whose target may be a process or a case: the model naming it cannot say which (a
     * variable extractor, an SLA, a query over instances). A call activity, a process task or a case
     * task can only start its own kind — a BPMN call activity never starts a case.
     */
    private val PROCESS_OR_CASE_RELS = setOf("extracts-from", "sla-of-process", "queries-process")

    private val PROCESS_OR_CASE = setOf("process", "case")

    /** App child-model types the app definition spells differently from the model kind. */
    private val KIND_ALIASES = mapOf("security" to "securityPolicy", "decisionService" to "decision")

    /** A value that is exactly one `${ident}` / `#{ident}` — the only shape we best-effort resolve. */

    private val EXPR_STRIP_RE = Regex("^[#$]\\{|\\}$")
    private val STR_LIT_IN_EXPR_RE = Regex("'[^']*'|\"[^\"]*\"")
    private val ROOT_IDENT_RE = Regex("(?<![\\w.\$])([A-Za-z_]\\w*)")
    private val MUSTACHE_HEAD_RE = Regex("^\\$?([A-Za-z_]\\w*)")
    private val DATA_OBJ_KEY_RE = Regex("dataObjectDefinitionKey=([A-Za-z0-9_.\\-]+)")
    private val QUERY_PATH_RE = Regex("/query/([A-Za-z0-9_.\\-]+)")
    private val IDENT_RE = Regex("[A-Za-z_]\\w*")

    /** Root identifiers a `{{…}}` placeholder may carry that are never project variables. */
    private val MUSTACHE_IGNORE = Constants.FRONTEND_SCRATCH_ROOTS

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
        // A simple name two classes give different values: resolving through it would be a guess.
        val ambiguousConstants = HashSet<String>()
        val javaOpCalls = LinkedHashMap<String, List<Map<String, String>>>()
        // fqn → (service keys it names, raw; operation keys it names; its own string-constant values)
        val javaServiceCalls = LinkedHashMap<String, Triple<List<String>, List<String>, Collection<String>>>()
        // Simple class names shared by more than one class — resolving through them is a guess
        // (first-wins), so any ref that falls back to such a name is flagged `suspect`. The same for a
        // bean name two classes both claim (`@Component("x")` twice, or a factory method of that name).
        val ambiguousSimple = HashSet<String>()
        val ambiguousBeans = HashSet<String>()
        for (path in javas) {
            val rel = relOf(path)
            // The read is inside the try as well: one unreadable source used to abort the whole run
            // with a stack trace instead of costing that one file.
            val srcText: String
            val jc: Map<String, Any?> = try {
                srcText = String(path.readBytes(), Charsets.UTF_8)
                JavaParser.parseJava(srcText, rel)
            } catch (e: Exception) {
                diag("java", rel, e.message ?: e.toString())
                continue
            }
            val primary = jc["primary"] as String
            val fqn = jc["fqn"] as String
            for (b in jc["beanNames"] as Collection<String>) if (beanIndex.putIfAbsent(b, jc) != null) ambiguousBeans.add(b)
            if (classIndex.putIfAbsent(primary, jc) != null) ambiguousSimple.add(primary)
            fqnIndex[fqn] = jc
            for (ep in jc["endpoints"] as List<Map<String, Any?>>) {
                val ep2 = LinkedHashMap(ep)
                ep2["file"] = rel
                // the annotated type, which need not be the file's first
                val ctl = (ep["controller"] as? String) ?: primary
                ep2["controller"] = ctl
                ep2["controllerFqn"] = if (ctl == primary) fqn else fqn.substringBeforeLast('.', "").let { if (it.isEmpty()) ctl else "$it.$ctl" }
                bucketList("endpoints").add(ep2)
            }
            if (jc["isController"] as Boolean) bucketList("javaControllers").add(jc)
            if (jc["isGlue"] as Boolean) bucketList("javaGlue").add(jc)
            for (role in jc["roles"] as Collection<String>) {
                javaByRole.getOrPut(role) { ArrayList() }.add(jc)
            }
            val ownConstants = JavaParser.stringConstants(srcText)
            for ((n, v) in ownConstants) {
                val prev = javaConstants.putIfAbsent(n, v)
                if (prev != null && prev != v) ambiguousConstants.add(n)
            }
            val ops = JavaParser.dataObjectOpCalls(srcText)
            if (ops.isNotEmpty()) javaOpCalls[fqn] = ops
            val (svcKeys, svcOps) = JavaParser.serviceInvocations(srcText)
            if (svcOps.isNotEmpty()) javaServiceCalls[fqn] = Triple(svcKeys, svcOps, ownConstants.values)
        }

        // ---- Constants in mapping paths: `@RequestMapping(ApiPaths.BASE)` with the constant in another file.
        // Resolved like a key constant; one nothing resolves — or a Spring `${…}` property — leaves the
        // path unknown, and an unknown path is kept out of matching instead of matching as a variable.
        for (ep in bucketList("endpoints")) {
            @Suppress("UNCHECKED_CAST") val e = ep as MutableMap<String, Any?>
            var path = e["path"] as? String ?: continue
            if (JavaParser.PATH_CONST in path) {
                path = Regex("${JavaParser.PATH_CONST}([^${JavaParser.PATH_CONST}]*)${JavaParser.PATH_CONST}").replace(path) { m ->
                    val n = m.groupValues[1]
                    (if (n in ambiguousConstants) null else javaConstants[n]) ?: ("\${" + n + "}")
                }
                e["path"] = "/" + path.split("/").filter { it.isNotEmpty() }.joinToString("/")
            }
            if ("\${" in (e["path"] as String)) e["pathUnresolved"] = true
        }

        // ---- Constants at key positions: `.caseDefinitionKey(ModelConstants.MAIN_CASE)` names the model the
        // constant's value names. Resolved now that every source is read, and recorded exactly like a
        // literal at the same position, so the graph builder draws the same confident edge. A name two
        // classes define differently stays unresolved rather than guessed.
        for (jc in fqnIndex.values) {
            val idents = jc["keyedIdents"] as? Collection<*> ?: continue
            val keyed = jc["keyedStrings"] as? MutableSet<String> ?: continue
            val strings = jc["strings"] as? MutableSet<String> ?: continue
            val kinds = jc["keyedKinds"] as? MutableMap<String, MutableSet<String>>
            val identKinds = jc["keyedIdentKinds"] as? Map<String, Set<String>>
            for (id in idents) {
                val n = id as? String ?: continue
                if (n in ambiguousConstants) continue
                val v = javaConstants[n] ?: continue
                keyed.add(v); strings.add(v)
                kinds?.getOrPut(v) { sortedSetOf() }?.addAll(identKinds?.get(n).orEmpty())
            }
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
                ctx.addOpUse(fqn, "dataObject", key, resolveDefKey(call["op"] ?: continue) ?: continue)
            }
        }

        // ---- Java service-registry invocations (…createServiceInvocationBuilder().serviceKey(s).operationKey("op")) ----
        // The operation belongs to the class's service: the one its `.serviceKey(…)` names, or else the one
        // a string constant of the class holds (a helper taking `SERVICE_KEY`). A class naming several
        // services credits each operation to the one service that defines it, and to none when that is
        // not decidable. Without this, an operation only Java calls was reported unused.
        val serviceOps = HashMap<String, Set<String>>()
        for (o in bucketList("services")) {
            val svc = o as? Map<*, *> ?: continue
            val key = svc["key"] as? String ?: continue
            serviceOps[key] = (svc["operations"] as? List<*>).orEmpty().mapNotNullTo(HashSet()) { (it as? Map<*, *>)?.get("key") as? String }
        }
        for ((fqn, call) in javaServiceCalls) {
            val (rawKeys, rawOps, ownConstants) = call
            val named = rawKeys.mapNotNull(::resolveDefKey).filter { it in serviceOps }.distinct()
            val candidates = named.ifEmpty { ownConstants.filter { it in serviceOps }.distinct() }
            for (op in rawOps.mapNotNull(::resolveDefKey).distinct()) {
                val target = candidates.filter { op in serviceOps.getValue(it) }.singleOrNull() ?: continue
                ctx.addOpUse(fqn, "service", target, op)
            }
        }

        // ---- Dedupe (the same model is often present both loose and inside a -bar.zip) ----
        fun <T> dedupe(items: List<T>, keyfn: (T) -> Any?): ArrayList<T> {
            val seen = HashSet<Any?>()
            val out = ArrayList<T>()
            for (it in items) if (seen.add(keyfn(it))) out.add(it)
            return out
        }
        // The source's type is part of the identity: a case and its start form share a key, and the same
        // reference from both is two references — collapsed, the form's one was credited to the case.
        fun refKey(r: Map<String, Any?>) = listOf(
            (r["fromType"] as? String)?.let { ModelKinds.NORMALIZE_TYPE[it] ?: it }, r["from"], r["rel"], r["kind"], r["value"],
        )

        replaceInPlace(ctx.refs, dedupe(ctx.refs, ::refKey))
        replaceInPlace(ctx.dynamicRefs, dedupe(ctx.dynamicRefs, ::refKey))
        replaceInPlace(ctx.restCalls, dedupe(ctx.restCalls) { rc -> listOf(rc["sourceId"] ?: rc["source"], rc["where"], rc["url"]) })
        for (bucket in ModelKinds.MODEL_BUCKETS) {
            val cur = bucketList(bucket)
            // Identity is (type, key), never the key alone: a form and a page, or a query and a
            // template, share a bucket and may legitimately share a key — the second one used to be
            // dropped here as a "duplicate", without a trace.
            replaceInPlace(cur, dedupe(cur) { o ->
                val m = o as? Map<*, *>
                val k = m?.get("key")
                if (k is String && k.isNotEmpty()) listOf(m["modelType"] ?: bucket, k) else Any()
            })
        }
        replaceInPlace(ctx.access, dedupe(ctx.access) { a ->
            listOf(a["modelType"], a["model"], a["scope"], a["action"], a["groups"], a["users"])
        })

        // ---- Platform query URLs carry model references in their query string / path ----
        for (rc in ctx.restCalls) {
            val url = (rc["url"] as? String) ?: ""
            val source = rc["source"]
            val sourceFile = (rc["sourceFile"] as? String) ?: ""
            // The originating model type is the one the call was recorded under; the kind is only the
            // fallback for a record without it (a page's call is not a form's).
            val srcType = (rc["sourceId"] as? String)?.substringBefore(':', "")?.takeIf { it.isNotEmpty() }
                ?: when (rc["kind"]) {
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

        // ---- Beans vs variables ----
        // `${x.method()}` reads like a bean call and like a method on a variable's value — `${issue.asText()}`,
        // `${attachments.size()}` — and the harvest cannot tell them apart. Every such root used to be a
        // bean: on four real projects 25 variables vanished from the variable graph and stood in the
        // "review — unresolved" list as beans nobody could find, and 15 service tasks reading a variable
        // were calls out of the engine. A root is a bean when something says so: the platform declares
        // it, Java declares it, a delegate expression names it bare, or its name is shaped like a bean's
        // ([Constants.looksLikeBeanName] — the one signal a Java-less Design export leaves). Everything
        // else is a variable, and the variable pass records the read.
        val declaredBeans = ctx.refs.asSequence()
            .filter { it["kind"] == "bean" && !isCallRel(it["rel"] as String) }
            .map { it["value"] as String }.toSet()
        fun isBean(name: String): Boolean {
            if (name in Constants.FLOWABLE_PLATFORM_BEANS || name in beanIndex || name in declaredBeans) return true
            val cap = if (name.isNotEmpty()) name[0].uppercaseChar() + name.substring(1) else name
            return cap in classIndex || Constants.looksLikeBeanName(name)
        }

        // ---- Resolve references ----
        val resolved = ArrayList<Map<String, Any?>>()
        val unresolved = ArrayList<Map<String, Any?>>()
        for (ref in ctx.refs) {
            val kind = ref["kind"] as String
            val value = ref["value"] as String
            // A method call on something that is not a bean is a variable read: nothing to resolve, and
            // no bean to report as unresolved.
            if (kind == "bean" && isCallRel(ref["rel"] as String) && !isBean(value)) continue
            var target: String? = null
            val ref2 = LinkedHashMap(ref)
            when {
                kind.startsWith("model:") || kind in MODEL_KIND_NAMES -> {
                    var norm = if (kind.startsWith("model:")) kind.substringAfter(":") else kind
                    norm = KIND_ALIASES[norm] ?: ModelKinds.NORMALIZE_TYPE[norm] ?: norm
                    target = modelIndex[norm to value]
                    ref2["target"] = target
                    ref2["targetType"] = "model"
                    // The resolved node type travels with the ref: the graph builder used to look the
                    // target up by key alone, so a formKey naming `orderX` landed on the *process*
                    // `orderX` whenever both existed — a clean-looking edge to the wrong model.
                    if (target != null) ref2["targetNodeType"] = norm
                    if (target == null && value in byKey) {
                        // Fallback across model types: a same-type entry, then a compatible one
                        // ([FALLBACK_COMPAT], process↔case only where the relation allows both). Any
                        // other type of that key is another model, and the reference stays missing.
                        val entries = byKey[value]!!
                        val compat = (FALLBACK_COMPAT[norm] ?: emptySet()) +
                            (if (ref["rel"] in PROCESS_OR_CASE_RELS && norm in PROCESS_OR_CASE) PROCESS_OR_CASE - norm else emptySet())
                        val match = entries.firstOrNull { it.first == norm }
                            ?: entries.firstOrNull { it.first in compat }
                        if (match != null) {
                            target = match.second
                            ref2["target"] = target
                            ref2["targetNodeType"] = match.first
                            if (match.first != norm) ref2["fallbackType"] = match.first
                        }
                    }
                }
                kind == "bean" -> {
                    val cap = if (value.isNotEmpty()) value[0].uppercaseChar() + value.substring(1) else value
                    val jc = beanIndex[value] ?: classIndex[cap]
                    // A factory bean points at its @Bean method, not at the configuration class's header.
                    val line = (jc?.get("beanMethods") as? Map<*, *>)?.get(value) ?: jc?.get("line")
                    target = if (jc != null) "${jc["file"]}:$line (${jc["fqn"]})" else null
                    ref2["target"] = target
                    ref2["targetType"] = "bean"
                    ref2["targetFqn"] = jc?.get("fqn")
                    // Resolved through a shared simple name or a bean name two classes claim — a
                    // first-wins guess either way, so keep it flagged.
                    if (jc != null && beanIndex[value] == null && cap in ambiguousSimple) ref2["suspect"] = true
                    if (jc != null && value in ambiguousBeans) ref2["suspect"] = true
                }
                kind == "class" -> {
                    val simple = value.substringAfterLast('.')
                    val jc = fqnIndex[value] ?: classIndex[simple]
                    target = if (jc != null) "${jc["file"]}:${jc["line"]}" else null
                    ref2["target"] = target
                    ref2["targetType"] = "class"
                    ref2["targetFqn"] = jc?.get("fqn")
                    if (jc != null && fqnIndex[value] == null && simple in ambiguousSimple) ref2["suspect"] = true
                    // A qualified name that matched only by its simple name names a class in another
                    // package — `org.flowable….Foo` from a library bound to the project's own `com.acme.Foo`.
                    if (jc != null && fqnIndex[value] == null && '.' in value) ref2["suspect"] = true
                }
                else -> {
                    ref2["target"] = null
                    ref2["targetType"] = kind
                }
            }
            (if (target != null) resolved else unresolved).add(ref2)
        }

        // ---- Dynamic (expression-valued) references ----
        // Rendered as `dynamic` edges to an expression placeholder node. They are not resolved: `${SUB}`
        // reads a variable or a bean when the engine evaluates it, never a Java constant of that name,
        // so linking it through one (as this once did) drew an edge to whichever model the constant names.
        for (ref in ctx.dynamicRefs) ref["dynamic"] = true

        // Must land in result BEFORE the graph step — it reads unresolvedRefs.
        result["resolvedRefs"] = resolved
        result["unresolvedRefs"] = unresolved
        result["dynamicRefs"] = ctx.dynamicRefs

        // ---- Resolve REST calls -> code endpoints ----
        val codeEndpoints = bucketList("endpoints") as List<Map<String, Any?>>
        for (rc in ctx.restCalls) {
            // the call's verb too: a GET is not answered by the POST handler on the same path
            val matchEps = JavaParser.matchRest(rc["url"] as? String, codeEndpoints, rc["method"] as? String)
            rc["_matchEps"] = matchEps
            fun describe(m: Map<String, Any?>) =
                "${m["http"]} ${m["path"]} -> ${m["controller"]}#${m["handler"]} (${m["file"]}:${m["line"]})" +
                    (if (m["methodMismatch"] == true) " (verb differs)" else "")
            // A path hit whose handler serves another verb is kept apart from the real matches: the call
            // does reach that path, but reporting it as "served by" states something untrue.
            // [JavaParser.matchRest] marks it `loose`.
            val (loose, clean) = matchEps.partition { it["loose"] == true }
            rc["matches"] = clean.map(::describe)
            rc["looseMatches"] = loose.map(::describe)
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
                // a call on a variable's value (`${order.getTotal()}`) leaves `order` to the identifier
                // scan below, where it is the variable it always was
                if (b !in Constants.FLOWABLE_CONTEXT && b !in Constants.JAVA_LITERALS && isBean(b)) {
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

        val knownBeans = LinkedHashSet<String>().apply {
            addAll(Constants.FLOWABLE_PLATFORM_BEANS); addAll(beanIndex.keys); addAll(declaredBeans); addAll(beans)
        }
        // `fqnIndex` is Python's `all_java` (both are `map[jc.fqn] = jc`, same loop, last wins).
        return Resolved(resolved, fqnIndex, beanMethods, knownBeans)
    }

    /** Replace the contents of [dst] with [src] in place (the Python `ctx[..] = _dedupe(..)` idiom). */
    private fun <T> replaceInPlace(dst: MutableList<T>, src: List<T>) {
        dst.clear()
        dst.addAll(src)
    }
}
