package com.flowable.atlas.graph

import com.flowable.atlas.model.Dyn
import com.flowable.atlas.expr.catalog.CustomFunctionCatalog
import com.flowable.atlas.expr.catalog.CustomFunctionExtractor
import com.flowable.atlas.parsing.Constants
import com.flowable.atlas.parsing.Discovery
import com.flowable.atlas.parsing.ModelKinds
import com.flowable.atlas.parsing.ModelParsers
import com.flowable.atlas.parsing.ModelSpans
import com.flowable.atlas.parsing.VarHarvest
import java.io.File

/**
 * The extraction orchestrator — a port of `flowable_atlas.py` `extract` + `dispatch` + `_index`
 * (~lines 1272-1382). Discovers a project's files, dispatches each model to its parser, harvests the
 * expressions / bindings / declared variables / delegate classes it references into the shared [Ctx],
 * and assembles the `result` structure.
 *
 * The tail of [extract] runs the Java pass and reference resolution ([ReferenceResolver]), the
 * Liquibase schema coverage ([LiquibaseCoverage]) and the graph build ([GraphBuilder], which also sets
 * each model's `_uses`). A model type without a structured parser goes through
 * [ModelParsers.parseGeneric] and is registered by key, name and description.
 *
 * Nothing is dropped silently: a file or archive entry that is not read — unparseable, not a model
 * wrapper, above [MAX_MODEL_BYTES], nested too deep — leaves a diagnostic behind, so the report can say
 * what it did not see.
 */
object Atlas {

    /** A model file or archive entry above this is not read; a `.form` with embedded images stays far below. */
    internal const val MAX_MODEL_BYTES: Long = 32L shl 20

    private val XML_MODEL_TYPES = setOf("bpmn", "cmmn", "dmn")

    private fun looksLikeJson(raw: String): Boolean {
        val i = raw.indexOfFirst { !it.isWhitespace() && it != '﻿' }
        if (i < 0) return false
        if (raw[i] == '[') return true
        if (raw[i] != '{') return false
        // `{{` opens a Go/Helm template, not a JSON object: after `{` JSON allows only `"` or `}`
        val j = raw.withIndex().drop(i + 1).firstOrNull { !it.value.isWhitespace() }?.value ?: return false
        return j == '"' || j == '}'
    }

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
        // (`javaBeans` used to be declared here and never written to — an always-empty key in every
        // graph.json, which a consumer can only read as "this project has no beans".)
        for (extra in listOf("javaControllers", "javaGlue", "endpoints", "warnings", "diagnostics")) {
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
            val known = byKey.getOrPut(key) { ArrayList() }
            // Two models of different types sharing one key is legal in Flowable and rare in practice,
            // and it is the one thing a key alone cannot settle: a reference that names only the key,
            // and the variables and expressions harvested under it, go to whichever type Atlas lists
            // first. Said once per (key, type), as a warning, so the reader knows which pages to doubt.
            // The same model loose and inside a .bar is the same type twice — not a conflict.
            if (known.none { it.first == norm }) {
                known.firstOrNull { it.first != norm }?.let { (otherType, otherLabel) ->
                    diag(
                        "conflict", label,
                        "key '$key' is shared with the $otherType model $otherLabel — references that " +
                            "name only the key, and the harvested variables and expressions, go to one of them",
                    )
                }
            }
            known.add(norm to label)
        }

        fun dispatch(mtype: String?, data: ByteArray, label: String) {
            if (mtype == null) return
            val raw = String(data, Charsets.UTF_8)
            // A JSON model type whose file is not JSON is not a Flowable model that failed to parse — it
            // is somebody else's file with the same extension (a Helm chart's `_helpers.tpl`, say). Said
            // as a skip, and before the harvest: a Go template is full of `{{ }}` that are not bindings.
            if (mtype !in XML_MODEL_TYPES && !looksLikeJson(raw)) {
                diag("skip", label, "not a JSON document — a .${label.substringAfterLast('.')} file that is no Flowable $mtype model")
                return
            }
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
                }
            } catch (e: Exception) {
                diag("parse", label, "($mtype) ${e.message}")
            }

            // Attribute what the raw text carries — every ${…} / {{…}}, every ${bean.method()} call,
            // every declared or mapped variable — to the model(s) in this file. A deployment XML may hold
            // several processes (or cases, or decisions): each gets only the text inside its own element,
            // and what sits outside all of them (the definitions header, messages, signals) goes to every
            // one. See ModelSpans for why crediting the whole file to each model was wrong.
            for ((keys, text) in ModelSpans.split(raw, mtype, mkeys)) {
                val ks = keys.filterNotNull()
                if (ks.isEmpty()) continue
                for (e in Constants.EXPR_RE.findAll(text).map { Constants.htmlUnescape(it.value) }) {
                    for (k in ks) ctx.exprUse.getOrPut(e) { LinkedHashSet() }.add(k.toString())
                }
                for (m in Constants.MUSTACHE_RE.findAll(text).map { Constants.htmlUnescape(it.value) }) {
                    for (k in ks) ctx.mustacheUse.getOrPut(m) { LinkedHashSet() }.add(k.toString())
                }
                if (parser != null) {
                    // Make ${bean.method()} references in this model visible (model → bean, labelled).
                    val calls = LinkedHashSet<Pair<String, String>>()
                    for (em in Constants.EXPR_RE.findAll(text)) {
                        for (cm in Constants.METHOD_CALL_FULL_RE.findAll(em.value)) {
                            val b = cm.groupValues[1]
                            val meth = cm.groupValues[2]
                            if (b !in Constants.FLOWABLE_CONTEXT && b !in Constants.JAVA_LITERALS) calls.add(b to meth)
                        }
                    }
                    for (k in ks) for ((b, meth) in calls) ctx.addRef(k, mtype, label, "calls $meth()", "bean", b)
                }
                VarHarvest.collectDeclaredVars(ctx, text, ks)
                VarHarvest.collectDirectedVars(ctx, text, ks)
            }

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

        // Older Design exports store each model as `<type>-models/<name>.json`
        // wrapping the body in {id, key, name, editorJson}. Unwrap and dispatch so those apps are not
        // invisible: modern-shaped bodies go straight to their parser (wrapper key/name injected);
        // Oryx-shaped bodies (stencil/childShapes — the old form/page editor) at least register the
        // model, so keys resolve and the raw ${…}/{{…}} harvest attributes to it.
        // Oryx-JSON twins of a process/case/decision that were skipped in favour of an XML sibling —
        // checked after discovery: a twin with no sibling was a whole model, silently.
        val skippedOryxTwins = ArrayList<Triple<String, String, String>>()   // (nodeType, key, label)

        fun dispatchDesignJson(folder: String?, data: ByteArray, label: String) {
            val wrapper = try {
                Dyn.mapOrNull(com.flowable.atlas.model.MiniJson.parse(String(data, Charsets.UTF_8)))
            } catch (e: Exception) {
                diag("parse", label, "(design json) ${e.message}"); return
            } ?: run { diag("skip", label, "JSON is not an object — not a Design model wrapper"); return }
            val key = wrapper["key"] as? String
                ?: run { diag("skip", label, "JSON carries no model key — not a Design model wrapper"); return }
            val ejRaw = wrapper["editorJson"]
                ?: run { diag("skip", label, "Design wrapper for '$key' carries no editorJson body"); return }
            @Suppress("UNCHECKED_CAST")
            val body: Map<String, Any?>? = when (ejRaw) {
                is Map<*, *> -> ejRaw as Map<String, Any?>
                is String -> try {
                    com.flowable.atlas.model.MiniJson.parse(ejRaw) as? Map<String, Any?>
                } catch (e: Exception) {
                    diag("parse", label, "(editorJson of '$key') ${e.message}"); null
                }
                else -> null
            }
            val mt = com.flowable.atlas.model.ModelType.byDesignFolder(folder)
            // a root-level wrapper whose body carries the app manifest is the (legacy) app model
                ?: if (body != null && (body.containsKey("flowApp") || body["models"] is List<*>)) {
                    com.flowable.atlas.model.ModelType.APP
                } else {
                    diag("skip", label, "'$key' sits in '${folder ?: "/"}', which is not a Design model folder — type unknown, not parsed")
                    return
                }
            val mtype = mt.parserKey
            // bpmn/cmmn/dmn json is the Oryx twin of an XML sibling in the same export — skip the copy,
            // but remember it: if no sibling turns up, that was the only copy.
            if (mtype in setOf("bpmn", "cmmn", "dmn")) {
                skippedOryxTwins.add(Triple(ModelKinds.NORMALIZE_TYPE[mtype] ?: mtype, key, label)); return
            }
            val oryx = body == null || body.containsKey("childShapes") || body.containsKey("stencil")
            val doc = LinkedHashMap<String, Any?>()
            if (mtype == "form" || mtype == "page" || (oryx && mtype == "app")) {
                // The wrapper first: it carries the model's name, and keeping it means the raw
                // ${…}/{{…}} harvest still sees an Oryx body that no parser can structurally read.
                doc.putAll(wrapper)
                // Then the PARSED body on top, with the now-redundant `editorJson` dropped. Design
                // persists `editorJson` as an escaped JSON *string*, and a form's components are reached
                // by walking maps (parseForm also reads `outcomes` / `outcomevariablename` at the top
                // level) — so a modern-shaped form or page in a Design workspace export produced a node
                // with no fields at all: no ids, no labels, no descriptions, nothing to search. Dropping
                // `editorJson` is what stops the walk from finding every component a second time.
                // Left alone for an Oryx body, where the merge would add nothing a parser can use.
                if (body != null && !oryx) {
                    doc.putAll(body)
                    doc.remove("editorJson")
                }
                // A modern body brings its own metadata header — including the model's description — so
                // build on it rather than replacing it; only the identity is the wrapper's to state.
                val bodyMeta = Dyn.mapOrNull(doc["metadata"])?.toMutableMap() ?: LinkedHashMap()
                bodyMeta["key"] = key
                bodyMeta["modelType"] = mtype
                if (truthyStr(wrapper["name"])) bodyMeta["name"] = wrapper["name"]
                doc["metadata"] = bodyMeta
            } else if (oryx) {
                // an Oryx body for a non-form type — nothing a parser could read, and no wrapper-only
                // registration either: say so rather than let the model vanish
                diag("skip", label, "'$key' ($mtype) has an Oryx-shaped body Atlas has no parser for — not registered")
                return
            } else {
                doc.putAll(body)
                doc.putIfAbsent("key", key)
                if (truthyStr(wrapper["name"])) doc.putIfAbsent("name", wrapper["name"])
                if (truthyStr(wrapper["description"])) doc.putIfAbsent("description", wrapper["description"])
                // the legacy action wrapper says `form`, the modern parser reads `formKey`
                if (mtype == "action" && doc["formKey"] == null && truthyStr(doc["form"])) doc["formKey"] = doc["form"]
            }
            dispatch(mtype, com.flowable.atlas.model.MiniJson.stringify(doc).toByteArray(Charsets.UTF_8), label)
        }

        val discovered = Discovery.discover(root)
        val isDir = root.isDirectory
        fun relOf(f: File): String = if (isDir) relativize(root, f) else f.name

        fun tooLarge(label: String, size: Long): Boolean {
            if (size <= MAX_MODEL_BYTES) return false
            diag("skip", label, "${size shr 20} MB is above the ${MAX_MODEL_BYTES shr 20} MB limit for one model file — not read")
            return true
        }

        for (path in discovered.models) {
            val rel = relOf(path)
            try {
                if (tooLarge(rel, path.length())) continue
                val mt = ModelKinds.modelTypeFor(path.name)
                if (mt != null) dispatch(mt, path.readBytes(), rel)
                else dispatchDesignJson(path.parentFile?.name, path.readBytes(), rel)
            } catch (e: Exception) {
                diag("read", rel, e.message ?: e.toString())
            }
        }

        /**
         * One archive entry: a model goes to its parser, a legacy-export JSON wrapper to
         * [dispatchDesignJson], and an archive *inside* the archive — a Design app export packing one
         * `.bar` per app is an ordinary shape — is opened one level down. [size] is -1 when the container
         * does not know it up front (a nested stream); then the cap is checked after reading.
         */
        fun scanEntry(entryName: String, size: Long, label: String, depth: Int, read: () -> ByteArray) {
            val base = entryName.substringAfterLast('/')
            val mt = ModelKinds.modelTypeFor(base)
            val isArchive = com.flowable.atlas.model.ModelPaths.isArchive(base)
            val isJson = base.lowercase().endsWith(".json")
            if (mt == null && !isArchive && !isJson) return    // an image, a class file — never read, nothing to say
            // The cap applies to what would be read; a 1 GB test image inside a zip is nobody's business.
            if (size >= 0 && tooLarge(label, size)) return
            if (isArchive) {
                if (depth >= 1) { diag("skip", label, "archive nested two levels deep — not opened"); return }
                val bytes = read()
                if (size < 0 && tooLarge(label, bytes.size.toLong())) return
                java.util.zip.ZipInputStream(bytes.inputStream()).use { zin ->
                    var inner = zin.nextEntry
                    while (inner != null) {
                        if (!inner.isDirectory) {
                            val innerLabel = "$label!${inner.name}"
                            try {
                                val innerBytes = zin.readBytes()
                                scanEntry(inner.name, innerBytes.size.toLong(), innerLabel, depth + 1) { innerBytes }
                            } catch (e: Exception) {
                                diag("archive", innerLabel, e.message ?: e.toString())
                            }
                        }
                        inner = zin.nextEntry
                    }
                }
                return
            }
            if (mt != null) {
                val bytes = read()
                if (size < 0 && tooLarge(label, bytes.size.toLong())) return
                dispatch(mt, bytes, label)
                return
            }
            // legacy-export JSON: `<type>-models/x.json` anywhere, or an app wrapper at the root
            val folder = entryName.split('/').dropLast(1).lastOrNull()
            if (com.flowable.atlas.model.ModelType.byDesignFolder(folder) == null && entryName.contains('/')) return
            dispatchDesignJson(folder, read(), label)
        }

        for (arc in discovered.archives) {
            val rel = relOf(arc)
            try {
                java.util.zip.ZipFile(arc).use { zf ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name.endsWith("/")) continue
                        val label = "$rel!${entry.name}"
                        try {
                            scanEntry(entry.name, entry.size, label, 0) { zf.getInputStream(entry).use { it.readBytes() } }
                        } catch (e: Exception) {
                            diag("archive", label, e.message ?: e.toString())
                        }
                    }
                }
            } catch (e: Exception) {
                diag("archive", rel, e.message ?: e.toString())
            }
        }

        // A skipped Oryx twin whose XML sibling never turned up was the only copy of that model.
        for ((nodeType, key, label) in skippedOryxTwins) {
            if (modelIndex[nodeType to key] == null) {
                diag("skip", label, "'$key' is a $nodeType in the legacy editor's JSON format with no XML twin in this project — not parsed")
            }
        }

        // Java parsing, reference resolution and REST matching. The returned holder carries the internal
        // structures the graph builder consumes — the full resolved-refs list (with `targetFqn`),
        // `all_java` (fqn → parsed java) and the `bean.method()` map — plus `byKey` above.
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

        // Health findings, derived from everything above (graph + buckets + diagnostics + custom fns),
        // so every renderer can state what is wrong instead of pointing at the explorer's Checks tab.
        Findings.apply(result)
        return result
    }

    private fun relativize(root: File, file: File): String =
        root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')

    private fun truthyStr(v: Any?): Boolean = (v as? String)?.isNotEmpty() == true
}
