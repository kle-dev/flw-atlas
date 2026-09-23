package com.flowable.atlas.graph

import com.flowable.atlas.model.Dyn
import com.flowable.atlas.expr.catalog.CustomFunctionCatalog
import com.flowable.atlas.expr.catalog.CustomFunctionExtractor
import com.flowable.atlas.parsing.Constants
import com.flowable.atlas.parsing.Discovery
import com.flowable.atlas.parsing.JsonPath
import com.flowable.atlas.parsing.ModelKinds
import com.flowable.atlas.parsing.ModelParsers
import com.flowable.atlas.parsing.ModelSpans
import com.flowable.atlas.parsing.ScriptMask
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

    /** A leftover marker as a whole word — `TODO` in `TODOS` or `xTODO` is not one. `XXX` is deliberately
     *  not one either: it is the placeholder of every format hint (`XXX-9999`) far more often than a mark. */
    private val MARKER_RE = Regex("(?<![A-Za-z0-9_])(TODO|FIXME|HACK)(?![A-Za-z0-9_])")

    /** The marker's own text: what follows it up to the end of the line, the string or the element it sits
     *  in — a minified form is one line, and the rest of *that* line is the rest of the file. A `}` or `]`
     *  inside the text is text (`TODO: handle the {order} case`); the string's own quote ends it. */
    private fun markerText(raw: String, from: Int): String {
        var end = raw.length
        for (i in from until raw.length) { val c = raw[i]; if (c == '\n' || c == '"' || c == '<' || c == '\\') { end = i; break } }
        return raw.substring(from, end).trim().removeSuffix("-->").removeSuffix("*/")
            .trimEnd('"', ',', ' ', ':', ')', '.').trimStart(':', '-', ' ', '(').trim().take(120)
    }

    /** A model file or archive entry above this is not read; a `.form` with embedded images stays far below. */
    internal const val MAX_MODEL_BYTES: Long = 32L shl 20

    private val XML_MODEL_TYPES = setOf("bpmn", "cmmn", "dmn")

    /** A `.data` model that is a master-data list, by the one attribute that says so. */
    private val MASTER_DATA_RE = Regex("\"dataObjectType\"\\s*:\\s*\"masterData\"")

    private fun looksLikeJson(raw: String): Boolean {
        val i = raw.indexOfFirst { !it.isWhitespace() && it != '﻿' }
        if (i < 0) return false
        if (raw[i] == '[') return true
        if (raw[i] != '{') return false
        // `{{` opens a Go/Helm template, not a JSON object: after `{` JSON allows only `"` or `}`.
        // An index loop, not `withIndex().drop()`, which copied the whole file into a list to find one character.
        var j = i + 1
        while (j < raw.length && raw[j].isWhitespace()) j++
        return j < raw.length && (raw[j] == '"' || raw[j] == '}')
    }

    /**
     * An entry's bytes, but never more than one past [MAX_MODEL_BYTES]: a stream whose size is not known up
     * front is read only far enough for the caller's size check to reject it.
     */
    private fun readCapped(input: java.io.InputStream): ByteArray = input.readNBytes((MAX_MODEL_BYTES + 1).toInt())

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
     *  - [waivers] — findings the project has decided to accept; they stay in the report, marked, and
     *    out of the counts.
     *  - [checkCanceled] — called between models and between phases; the IDE passes its progress
     *    indicator's check, which throws to abandon the run. A cancelled generation used to run to the
     *    end and overwrite the artifacts anyway.
     */
    fun extract(
        root: File,
        exprAllowlist: Set<String>? = null,
        discoverCustom: Boolean = true,
        customPath: File? = null,
        waivers: Waivers.Set = Waivers.EMPTY,
        checkCanceled: () -> Unit = {},
    ): LinkedHashMap<String, Any?> {
        val ctx = Ctx()
        val result = LinkedHashMap<String, Any?>()
        for (bucket in ModelKinds.MODEL_BUCKETS) result[bucket] = ArrayList<Any?>()
        // (`javaBeans` used to be declared here and never written to — an always-empty key in every
        // graph.json, which a consumer can only read as "this project has no beans".)
        for (extra in listOf("javaControllers", "javaGlue", "endpoints", "warnings", "diagnostics", "markers")) {
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
            // Two models of different types sharing one key is legal in Flowable and common in practice
            // — a case and its start form, a data object and its generated service. Everything a parser
            // records about a model carries the type now, so only a reference that names the key alone
            // (a Java string literal) is ambiguous; it goes to whichever type Atlas lists first and is
            // marked suspect. Recorded once per (key, type) as information, not as a finding: nothing
            // failed. The same model loose and inside a .bar is the same type twice — not a conflict.
            if (known.none { it.first == norm }) {
                known.firstOrNull { it.first != norm }?.let { (otherType, otherLabel) ->
                    diag(
                        "conflict", label,
                        "key '$key' is shared with the $otherType model $otherLabel — a reference that " +
                            "names only the key (a Java string literal) reaches one of them, marked suspect",
                    )
                }
            }
            known.add(norm to label)
        }

        fun dispatch(mtypeByFile: String?, data: ByteArray, label: String) {
            if (mtypeByFile == null) return
            val raw = String(data, Charsets.UTF_8)
            // A JSON model type whose file is not JSON is not a Flowable model that failed to parse — it
            // is somebody else's file with the same extension (a Helm chart's `_helpers.tpl`, say). Said
            // as a skip, and before the harvest: a Go template is full of `{{ }}` that are not bindings.
            if (mtypeByFile !in XML_MODEL_TYPES && !looksLikeJson(raw)) {
                diag("skip", label, "not a JSON document — a .${label.substringAfterLast('.')} file that is no Flowable $mtypeByFile model")
                return
            }
            // The extension says `.data`; only the body says whether it is a data object or a master-data
            // list. The type decides the node, the bucket and the index entry, so it is settled here.
            val mtype = if (mtypeByFile == "dataObject" && MASTER_DATA_RE.containsMatchIn(raw)) "masterData" else mtypeByFile
            // Script bodies are read by their own parsers; to the text harvest their `${…}` is string
            // interpolation, not an expression (see ScriptMask). Markers and delegate classes still read `raw`.
            val harvest = ScriptMask.mask(raw, xml = mtype in XML_MODEL_TYPES)
            val exprs = Constants.EXPR_RE.findAll(harvest).map { Constants.htmlUnescape(it.value) }.toCollection(LinkedHashSet())
            val musts = Constants.MUSTACHE_RE.findAll(harvest).map { Constants.htmlUnescape(it.value) }.toCollection(LinkedHashSet())
            ctx.expr.addAll(exprs)
            ctx.mustache.addAll(musts)
            Constants.DELEGATE_CLASS_RE.findAll(raw).forEach { ctx.delegateClasses.add(it.groupValues[1]) }

            val parser = ModelParsers.PARSERS[mtype]
            val mkeys = ArrayList<Any?>()
            // Everything the parser and the harvest below record about this file's models carries this
            // type (Ctx.modelId), so a key two types share cannot be mis-credited.
            val nodeType = ModelKinds.NORMALIZE_TYPE[mtype] ?: mtype
            ctx.currentModel = nodeType
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

            // A TODO, FIXME or HACK in a model file is a promise someone made to come back. Kept with its
            // line and the model it sits in — the process whose element holds it when the file defines
            // several, else every model of the file — so the report can list the ones nobody has kept. The
            // text after the marker is the subject, which survives a line moving; a marker with no text in
            // a minified JSON model gets the path of the element carrying it, which survives a re-export.
            val markerNodes = mkeys.filterNotNull().map { "$nodeType:$it" }
            val spans = ModelSpans.ranges(raw, mtype, mkeys)
            for (m in MARKER_RE.findAll(raw)) {
                val before = raw.substring(0, m.range.first)
                val owner = spans?.firstOrNull { m.range.first in it.second }?.first
                val rec = linkedMapOf<String, Any?>(
                    "file" to label, "line" to before.count { it == '\n' } + 1,
                    "marker" to m.groupValues[1], "text" to markerText(raw, m.range.last + 1),
                    "models" to (if (owner != null) listOf("$nodeType:$owner") else markerNodes),
                )
                if (mtype !in XML_MODEL_TYPES) JsonPath.at(raw, m.range.first)?.let { rec["path"] = it }
                bucketList("markers").add(rec)
            }

            // Attribute what the raw text carries — every ${…} / {{…}}, every ${bean.method()} call,
            // every declared or mapped variable — to the model(s) in this file. A deployment XML may hold
            // several processes (or cases, or decisions): each gets only the text inside its own element,
            // and what sits outside all of them (the definitions header, messages, signals) goes to every
            // one. See ModelSpans for why crediting the whole file to each model was wrong.
            for ((keys, text) in ModelSpans.split(harvest, mtype, mkeys)) {
                val ks = keys.filterNotNull()
                if (ks.isEmpty()) continue
                for (e in Constants.EXPR_RE.findAll(text).map { Constants.htmlUnescape(it.value) }) {
                    for (k in ks) ctx.exprUse.getOrPut(e) { LinkedHashSet() }.add("$nodeType:$k")
                }
                for (m in Constants.MUSTACHE_RE.findAll(text).map { Constants.htmlUnescape(it.value) }) {
                    for (k in ks) ctx.mustacheUse.getOrPut(m) { LinkedHashSet() }.add("$nodeType:$k")
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
            ctx.currentModel = null
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
                } else if (body != null && mtype != "app") {
                    // The legacy editor's shape, rewritten into the one parseForm reads: every
                    // Design-workspace export is this shape, and it used to register by key with no
                    // fields — see OryxFormReader.
                    doc.putAll(com.flowable.atlas.parsing.OryxFormReader.toModern(body))
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
            checkCanceled()
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
        // Liquibase changelogs found inside archives: a Design export packs `liquibase-<key>.data.changelog.xml`
        // next to the models it belongs to. Handed to LiquibaseCoverage together with the loose ones.
        val archiveChangelogs = ArrayList<Pair<String, String>>()
        // A template's body lives beside its `.tpl` in a BAR — `template-<key>.tplvariation` — and an attached
        // file's name in `.tplfile-metadata`. Parts, not models: attached to the template after the walk.
        val templateParts = ArrayList<Pair<String, String>>()

        fun scanEntry(entryName: String, size: Long, label: String, depth: Int, read: () -> ByteArray) {
            val base = entryName.substringAfterLast('/')
            val mt = ModelKinds.modelTypeFor(base)
            val isArchive = com.flowable.atlas.model.ModelPaths.isArchive(base)
            val isJson = base.lowercase().endsWith(".json")
            val low = base.lowercase()
            if (Discovery.isTemplatePart(low)) {
                if (size in 0..MAX_MODEL_BYTES || size < 0) {
                    val bytes = read()
                    if (bytes.size <= MAX_MODEL_BYTES) templateParts.add(label to String(bytes, Charsets.UTF_8))
                }
                return
            }
            if (mt == null && !isArchive && (low.endsWith(".xml") || low.endsWith(".sql"))) {
                // a changelog candidate; LiquibaseCoverage keeps only what is one. An oversized resource
                // is not a model that failed, so it is left alone without a word.
                if (size in 0..MAX_MODEL_BYTES || size < 0) {
                    val bytes = read()
                    if (bytes.size <= MAX_MODEL_BYTES) archiveChangelogs.add(label to String(bytes, Charsets.UTF_8))
                }
                return
            }
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
                                // Named and sized before anything is read: an image, a jar or a zip bomb
                                // inside the inner archive is skipped, not inflated into memory first.
                                scanEntry(inner.name, inner.size, innerLabel, depth + 1) { readCapped(zin) }
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
            // legacy-export JSON: `<type>-models/x.json` anywhere, or an app wrapper at the root. A wrapper
            // in a folder Design does not use is a model Atlas cannot type — said, not dropped (one real
            // export lost a decision service that way); any other JSON in a subfolder is nobody's model.
            val folder = entryName.split('/').dropLast(1).lastOrNull()
            val bytes = read()
            if (size < 0 && tooLarge(label, bytes.size.toLong())) return
            if (com.flowable.atlas.model.ModelType.byDesignFolder(folder) == null && entryName.contains('/') &&
                !String(bytes, Charsets.UTF_8).contains("\"editorJson\"")) return
            dispatchDesignJson(folder, bytes, label)
        }

        for (arc in discovered.archives) {
            // Outside the try below: its catch would record a cancellation as an unreadable archive.
            checkCanceled()
            val rel = relOf(arc)
            try {
                java.util.zip.ZipFile(arc).use { zf ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name.endsWith("/")) continue
                        val label = "$rel!${entry.name}"
                        try {
                            scanEntry(entry.name, entry.size, label, 0) { zf.getInputStream(entry).use(::readCapped) }
                        } catch (e: Exception) {
                            diag("archive", label, e.message ?: e.toString())
                        }
                    }
                }
            } catch (e: Exception) {
                diag("archive", rel, e.message ?: e.toString())
            }
        }

        for (f in discovered.templateParts) {
            try { templateParts.add(relOf(f) to f.readText(Charsets.UTF_8)) } catch (e: Exception) { diag("parse", relOf(f), e.message ?: e.toString()) }
        }
        TemplateParts.attach(result, ctx, templateParts) { kind, path, msg -> diag(kind, path, msg) }

        // A skipped Oryx twin whose XML sibling never turned up was the only copy of that model.
        for ((nodeType, key, label) in skippedOryxTwins) {
            if (modelIndex[nodeType to key] == null) {
                diag("skip", label, "'$key' is a $nodeType in the legacy editor's JSON format with no XML twin in this project — not parsed")
            }
        }

        // Java parsing, reference resolution and REST matching. The returned holder carries the internal
        // structures the graph builder consumes — the full resolved-refs list (with `targetFqn`),
        // `all_java` (fqn → parsed java) and the `bean.method()` map — plus `byKey` above.
        // Changelogs are models an app lists among its children (`model:liquibase`), so they must be in
        // the index before references resolve — or every app pointing at its own changelog reports a
        // missing model. Indexed by the key their file name carries, never in `byKey`: a changelog named
        // after its service is the expected shape, not a clash.
        val looseChangelogs = discovered.xmls.mapNotNull { f ->
            // A 2 GB data.sql dump is not a changelog anyone wrote by hand; like an oversized archive
            // resource it is left alone without a word.
            if (f.length() > MAX_MODEL_BYTES) return@mapNotNull null
            val txt = try { f.readText(Charsets.UTF_8) } catch (e: Exception) { return@mapNotNull null }
            relOf(f) to txt
        }
        val changelogs = looseChangelogs + archiveChangelogs
        for ((rel, txt) in changelogs) {
            if (LiquibaseCoverage.isChangelog(txt)) modelIndex.putIfAbsent("liquibase" to LiquibaseCoverage.keyOf(rel), rel)
        }

        checkCanceled()
        val resolvedData = ReferenceResolver.resolve(
            result, ctx, modelIndex, byKey, discovered.javas,
            { f -> relOf(f) }, { kind, path, msg -> diag(kind, path, msg) },
        )

        // Liquibase schema coverage: enrich data objects + services and build the changelog entries
        // (Python `_enrich_data_objects` / `_schema_coverage` / `_mark_liquibase_authority`).
        LiquibaseCoverage.apply(result, changelogs)

        // Column mappings that pair a field with another field's column. Reads the coverage rows above
        // for the table's unmapped columns, so it has to run after it.
        CrossedColumns.apply(result)

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
        checkCanceled()
        GraphBuilder.build(
            result, ctx, resolvedData.resolved, resolvedData.allJava, resolvedData.beanMethods, resolvedData.knownBeans, byKey,
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
        checkCanceled()
        Findings.apply(result, waivers)
        return result
    }

    private fun relativize(root: File, file: File): String =
        root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')

    private fun truthyStr(v: Any?): Boolean = (v as? String)?.isNotEmpty() == true
}
