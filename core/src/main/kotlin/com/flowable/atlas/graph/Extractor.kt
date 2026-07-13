package com.flowable.atlas.graph

import com.flowable.atlas.expr.catalog.CustomFunctionCatalog
import com.flowable.atlas.expr.catalog.CustomFunctionExtractor
import com.flowable.atlas.parsing.Constants
import com.flowable.atlas.parsing.Discovery
import com.flowable.atlas.parsing.ModelKinds
import com.flowable.atlas.parsing.ModelParsers
import com.flowable.atlas.parsing.VarHarvest
import java.io.File

/**
 * The extraction orchestrator — a port of `flowable_atlas.py` `extract` + `dispatch` + `_index`
 * (~lines 1272-1382). Discovers a project's files, dispatches each model to its parser, harvests the
 * expressions / bindings / declared variables / delegate classes it references into the shared [Ctx],
 * and assembles the `result` structure.
 *
 * Ported incrementally: Java parsing, reference resolution, Liquibase coverage and `_build_graph`
 * (which sets each model's `_uses` and the node/edge graph) are added in later increments. Model
 * types whose parser is not yet ported would be skipped, but all parse_* are now wired.
 */
object Atlas {

    private val QUERY_KEY_RE = Regex("\"key\"\\s*:\\s*\"([^\"]+)\"")
    private val QUERY_GROUPS_RE = Regex("seq_contains\\(\\s*\\\\?\"([A-Za-z0-9_.\\-]+)")
    private val QUERY_SOURCE_RE = Regex("\"sourceIndex\"\\s*:\\s*\"([^\"]+)\"")

    /**
     * Run extraction over [root] (a project directory or a single `.zip`/`.bar` archive).
     *
     * Mirrors the Python `extract(root, expr_allowlist, custom, discover_custom, custom_path)`:
     *  - [exprAllowlist] — expression-function namespaces/members the project provides itself; passed
     *    to the graph builder so matching "unknown function" findings are suppressed, not flagged.
     *  - [discoverCustom] — when true (default), scan for `externals.additionalData` custom functions.
     *  - [customPath] — explicit frontend-customization source (dir or index file); defaults to [root].
     */
    fun extract(
        root: File,
        exprAllowlist: Set<String>? = null,
        discoverCustom: Boolean = true,
        customPath: File? = null,
    ): LinkedHashMap<String, Any?> {
        val ctx = Ctx()
        val result = LinkedHashMap<String, Any?>()
        for (bucket in ModelKinds.MODEL_BUCKETS) result[bucket] = ArrayList<Any?>()
        for (extra in listOf("javaBeans", "javaControllers", "javaGlue", "endpoints", "warnings", "diagnostics")) {
            result[extra] = ArrayList<Any?>()
        }
        val modelIndex = LinkedHashMap<Pair<String, String>, String>()
        val byKey = LinkedHashMap<String, MutableList<Pair<String, String>>>()

        @Suppress("UNCHECKED_CAST")
        fun bucketList(name: String) = result[name] as ArrayList<Any?>

        fun diag(kind: String, path: String, message: String) {
            bucketList("diagnostics").add(linkedMapOf("kind" to kind, "path" to path, "message" to message))
            bucketList("warnings").add("$kind $path: $message")
        }

        fun index(mtype: String, obj: Any?, label: String) {
            val key = (obj as? Map<*, *>)?.get("key") as? String ?: return
            val norm = ModelKinds.NORMALIZE_TYPE[mtype] ?: mtype
            modelIndex[norm to key] = label
            byKey.getOrPut(key) { ArrayList() }.add(norm to label)
        }

        fun dispatch(mtype: String?, data: ByteArray, label: String) {
            if (mtype == null) return
            val raw = String(data, Charsets.UTF_8)
            val exprs = Constants.EXPR_RE.findAll(raw).map { Constants.htmlUnescape(it.value) }.toCollection(LinkedHashSet())
            val musts = Constants.MUSTACHE_RE.findAll(raw).map { Constants.htmlUnescape(it.value) }.toCollection(LinkedHashSet())
            ctx.expr.addAll(exprs)
            ctx.mustache.addAll(musts)
            Constants.DELEGATE_CLASS_RE.findAll(raw).forEach { ctx.delegateClasses.add(it.groupValues[1]) }

            val parser = ModelParsers.PARSERS[mtype]
            val mkeys = ArrayList<Any?>()
            try {
                if (parser == null) {
                    val obj = ModelParsers.parseGeneric(data, ctx, label, mtype)
                    bucketList("others").add(obj); index(mtype, obj, label); mkeys.add(obj["key"])
                } else {
                    val bucket = ModelKinds.MODEL_BUCKET[mtype]!!
                    when (val parsed = parser(data, ctx, label)) {
                        is List<*> -> parsed.forEach { p ->
                            bucketList(bucket).add(p)
                            index(if (mtype == "bpmn") "process" else mtype, p, label)
                            mkeys.add((p as? Map<*, *>)?.get("key"))
                        }
                        else -> {
                            bucketList(bucket).add(parsed)
                            index(mtype, parsed, label)
                            mkeys.add((parsed as? Map<*, *>)?.get("key"))
                        }
                    }
                    // Make ${bean.method()} references in this model visible (model → bean, labelled).
                    val calls = LinkedHashSet<Pair<String, String>>()
                    for (em in Constants.EXPR_RE.findAll(raw)) {
                        for (cm in Constants.METHOD_CALL_FULL_RE.findAll(em.value)) {
                            val b = cm.groupValues[1]
                            val meth = cm.groupValues[2]
                            if (b !in Constants.FLOWABLE_CONTEXT && b !in Constants.JAVA_LITERALS) calls.add(b to meth)
                        }
                    }
                    for (k in mkeys) for ((b, meth) in calls) ctx.addRef(k, mtype, label, "calls $meth()", "bean", b)
                }
            } catch (e: Exception) {
                diag("parse", label, "($mtype) ${e.message}")
            }

            // Attribute every ${…} / {{…}} occurrence to the model(s) in this file.
            for (k in mkeys) {
                if (k == null) continue
                for (e in exprs) ctx.exprUse.getOrPut(e) { LinkedHashSet() }.add(k.toString())
                for (m in musts) ctx.mustacheUse.getOrPut(m) { LinkedHashSet() }.add(k.toString())
            }
            VarHarvest.collectDeclaredVars(ctx, raw, mkeys)

            if (mtype == "query") {
                QUERY_KEY_RE.find(raw)?.let { km ->
                    @Suppress("UNCHECKED_CAST")
                    val meta = ctx.queryMeta.getOrPut(km.groupValues[1]) {
                        linkedMapOf("groups" to LinkedHashSet<String>(), "sourceIndex" to null)
                    }
                    val gs = QUERY_GROUPS_RE.findAll(raw).map { it.groupValues[1] }.toSet()
                    @Suppress("UNCHECKED_CAST")
                    val groups = meta["groups"] as MutableSet<String>
                    groups.addAll(gs)
                    ctx.groups.addAll(gs)
                    val si = QUERY_SOURCE_RE.find(raw)
                    if (si != null && meta["sourceIndex"] == null) meta["sourceIndex"] = si.groupValues[1]
                }
            }
        }

        val discovered = Discovery.discover(root)
        val isDir = root.isDirectory
        fun relOf(f: File): String = if (isDir) relativize(root, f) else f.name

        for (path in discovered.models) {
            val rel = relOf(path)
            try {
                dispatch(ModelKinds.modelTypeFor(path.name), path.readBytes(), rel)
            } catch (e: Exception) {
                diag("read", rel, e.message ?: e.toString())
            }
        }

        for (arc in discovered.archives) {
            val rel = relOf(arc)
            try {
                java.util.zip.ZipFile(arc).use { zf ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name.endsWith("/")) continue
                        val mt = ModelKinds.modelTypeFor(entry.name.substringAfterLast('/')) ?: continue
                        val bytes = zf.getInputStream(entry).use { it.readBytes() }
                        dispatch(mt, bytes, "$rel!${entry.name}")
                    }
                }
            } catch (e: Exception) {
                diag("archive", rel, e.message ?: e.toString())
            }
        }

        // Java parsing, reference resolution, REST matching and the result-section assembly.
        // (Liquibase coverage and the navigable graph are added in later increments.)
        // The returned holder carries the internal structures the graph builder consumes —
        // the full resolved-refs list (with `targetFqn`), `all_java` (fqn → parsed java) and
        // the `bean.method()` map — plus `byKey` above; wired into GraphBuilder.build(...) below.
        val resolvedData = ReferenceResolver.resolve(
            result, ctx, modelIndex, byKey, discovered.javas,
            { f -> relOf(f) }, { kind, path, msg -> diag(kind, path, msg) },
        )

        // Liquibase schema coverage: enrich data objects + services and build the changelog entries
        // (Python `_enrich_data_objects` / `_schema_coverage` / `_mark_liquibase_authority`).
        LiquibaseCoverage.apply(result, discovered.xmls, root)

        // Discovery counts feed `result["stats"]` (Python's `len(models)/len(archives)/len(javas)`).
        ctx.modelFileCount = discovered.models.size
        ctx.archiveFileCount = discovered.archives.size
        ctx.javaFileCount = discovered.javas.size

        // Custom frontend functions (externals.additionalData) — parity with Python's
        // `if custom is None and discover_custom: custom = extract_custom_functions(custom_path or root, ...)`.
        // Never let extraction abort a run: on failure record a diagnostic and fall back to null.
        var custom: CustomFunctionCatalog? = null
        if (discoverCustom) {
            try {
                custom = CustomFunctionExtractor.extract(customPath ?: root, explicit = customPath)
            } catch (e: Exception) {
                diag("custom-functions", (customPath ?: root).path, e.message ?: e.toString())
                custom = null
            }
        }

        // Navigable graph (nodes + edges) + `_uses` enrichment + stats — Python `_build_graph`.
        // The graph builder receives the raw catalog + allowlist (Python `_build_graph(..., expr_allowlist, custom)`).
        GraphBuilder.build(
            result, ctx, resolvedData.resolved, resolvedData.allJava, resolvedData.beanMethods, byKey,
            exprAllowlist = exprAllowlist, custom = custom,
        )

        // Mirror Python's `result.update({... "customFunctions": {...} if custom else None ...})`:
        // ReferenceResolver already set this key to null; overwrite it with the summary shape when
        // custom functions were found (namespace member lists sorted, like Python's dict comprehension).
        if (custom != null) {
            result["customFunctions"] = linkedMapOf(
                "namespaces" to custom.namespaces.mapValues { it.value.sorted() },
                "flw" to custom.flw.sorted(),
                "topLevel" to custom.topLevel.sorted(),
                "sources" to custom.sources,
                "diagnostics" to custom.diagnostics,
                "signatures" to custom.signatures,
                "summary" to custom.summary(),
            )
        }
        return result
    }

    private fun relativize(root: File, file: File): String =
        root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
}
