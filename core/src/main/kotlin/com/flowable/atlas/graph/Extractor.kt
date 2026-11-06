package com.flowable.atlas.graph

import com.flowable.atlas.model.Dyn
import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.model.ModelPaths
import com.flowable.atlas.expr.catalog.CustomFunctionCatalog
import com.flowable.atlas.expr.catalog.CustomFunctionExtractor
import com.flowable.atlas.parsing.AtlasXml
import com.flowable.atlas.parsing.Constants
import com.flowable.atlas.parsing.Discovery
import com.flowable.atlas.parsing.JsonPath
import com.flowable.atlas.parsing.ModelKinds
import com.flowable.atlas.parsing.ModelParsers
import com.flowable.atlas.parsing.ModelSpans
import com.flowable.atlas.parsing.ScriptMask
import com.flowable.atlas.parsing.SpringProperties
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

    private val PATH_SEG_RE = Regex("""([^.\[\]]+)|\[(\d+)]""")

    /**
     * Where a marker sits in a JSON model, from its [JsonPath]: the whole string holding it — a label reading
     * `Current Pod (TODO)`, where the text after the marker is only `)` — and the id of the innermost element
     * around it, a form component or a shape. With them the finding names the element and what it says,
     * and the page can point at it; with only the path a reader had `rows[1].cols[0].label` to go on.
     */
    private fun markerSite(doc: Any?, path: String): Pair<String?, String?> {
        var cur: Any? = doc
        var id: String? = null
        for (seg in PATH_SEG_RE.findAll(path)) {
            val idx = seg.groupValues[2]
            cur = if (idx.isNotEmpty()) (cur as? List<*>)?.getOrNull(idx.toInt()) else (cur as? Map<*, *>)?.get(seg.groupValues[1])
            ((cur as? Map<*, *>)?.get("id") as? String)?.takeIf { it.isNotBlank() }?.let { id = it }
        }
        return (cur as? String)?.replace(Regex("\\s+"), " ")?.trim()?.take(160) to id
    }

    /**
     * A model file or archive entry above this is not read; a `.form` with embedded images stays far below.
     * Public because the IDE's model index applies the same cap — otherwise the two disagree about what
     * an oversized model is, and the index read without any limit at all.
     */
    const val MAX_MODEL_BYTES: Long = 32L shl 20

    /**
     * How much changelog and template text one run keeps for after the walk, all files and archive entries
     * together. Each of them is read and checked on its own, but they are all held until the Liquibase
     * replay and the template pass at the end — so a 1.3 MB archive of forty 31 MB entries filled a 1 GB
     * heap before anything looked at them. The real projects hold between 72 KB and 6 MB of it.
     */
    const val MAX_RETAINED_TEXT_BYTES: Long = 8 * MAX_MODEL_BYTES

    private val XML_MODEL_TYPES = setOf("bpmn", "cmmn", "dmn")

    /** A `.data` model that is a master-data list, by the one attribute that says so. */
    private val STRING_LITERAL_RE = Regex("'[^']*'|\"[^\"]*\"")
    private val FREEMARKER_TYPES = setOf("template", "document", "query")
    // `<#list items as a, b>` / `<#items as a>`, `<#assign|local|global a …>`, `<#macro m p1 p2>`
    private val FTL_LOCAL_RE = Regex("""<#(?:list\b[^>]*?\bas\s+([\w\s,]+?)\s*>|items\s+as\s+([\w\s,]+?)\s*>|(?:assign|local|global)\s+(\w+)|macro\s+\w+((?:\s+\w+)*))""")
    private val IDENT_RE = Regex("[A-Za-z_]\\w*")
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
     *  - [retainedTextBudget] — see [MAX_RETAINED_TEXT_BYTES]; a test lowers it rather than writing gigabytes.
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
        retainedTextBudget: Long = MAX_RETAINED_TEXT_BYTES,
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

        /** [line] is where in the file the problem is, when the parser said: the finding opens the file there. */
        fun diag(kind: String, path: String, message: String, line: Int? = null) {
            bucketList("diagnostics").add(linkedMapOf<String, Any?>("kind" to kind, "path" to path, "message" to message)
                .apply { if (line != null) put("line", line) })
            bucketList("warnings").add("$kind $path${if (line != null) ":$line" else ""}: $message")
        }

        // The first file of every (type, key), with its model, and the references each copy made: the same
        // model twice — loose and inside a `.bar`, a Design export beside the deployed one — is one node.
        val copies = HashMap<Pair<String, String>, Pair<String, Any?>>()
        val copyRefs = HashMap<Pair<String, String>, Pair<String, String>>()   // (type, key|format) → (first file, refs)
        fun index(mtype: String, obj: Any?, label: String) {
            val key = (obj as? Map<*, *>)?.get("key") as? String ?: return
            val norm = ModelKinds.NORMALIZE_TYPE[mtype] ?: mtype
            copies.putIfAbsent(norm to key, label to obj)
            // One winner for every reader: the copy the node, the bucket dedupe and `copies` keep — the first
            // deployed one, since test sources are read last (see the dispatch order below) — is the file the
            // index names. The index used to let every later copy replace the earlier one, so with a `.bar`
            // and a Design export side by side a reference and the overview's model listing named the export
            // while the model's own entry named the deployed archive.
            modelIndex[norm to key] = copies.getValue(norm to key).first
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
                        "key '$key' is shared with the $otherType model $otherLabel — a Java string literal " +
                            "that names only the key, outside a key-taking API, reaches one of them, marked suspect",
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
            // a DRD's decisions, whose ids every DRD Design exports numbers from 1 — see the copy check below
            val drdKeys = HashSet<Any?>()
            val refsBefore = ctx.refs.size
            // Everything the parser and the harvest below record about this file's models carries this
            // type (Ctx.modelId), so a key two types share cannot be mis-credited. The form parser may
            // correct it — a `.form` whose metadata says `page` is a page — and then everything after the
            // parse follows the correction; left at the extension's type, the page's bindings, variables
            // and references went to `form:X`, which does not exist, and on to whatever else is called X.
            var nodeType = ModelKinds.NORMALIZE_TYPE[mtype] ?: mtype
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
                            if ((p as? Map<*, *>)?.get("embeddedIn") != null) drdKeys.add(p["key"])
                        }
                        else -> {
                            nodeType = ctx.currentModel ?: nodeType
                            bucketList(bucket).add(parsed)
                            index(nodeType, parsed, label)
                            mkeys.add((parsed as? Map<*, *>)?.get("key"))
                        }
                    }
                }
            } catch (e: Exception) {
                diag("parse", label, "($mtype) ${e.message}", AtlasXml.lineOf(e))
            }

            // A TODO, FIXME or HACK in a model file is a promise someone made to come back. Kept with its
            // line and the model it sits in — the process whose element holds it when the file defines
            // several, else every model of the file — so the report can list the ones nobody has kept. The
            // text after the marker is the subject, which survives a line moving; a marker with no text in
            // a minified JSON model gets the path of the element carrying it, which survives a re-export.
            val markerNodes = mkeys.filterNotNull().map { "$nodeType:$it" }
            val spans = ModelSpans.ranges(raw, mtype, mkeys)
            val jsonDoc by lazy { if (mtype in XML_MODEL_TYPES) null else runCatching { MiniJson.parse(raw) }.getOrNull() }
            val minified = raw.indexOf('\n').let { it < 0 || it >= raw.trimEnd().length }
            // The pattern starts with a lookbehind, so it is tried at every position of the file — costly
            // across a whole project for a handful of matches; a plain search says first whether there is
            // anything to find.
            val markers = if (raw.contains("TODO") || raw.contains("FIXME") || raw.contains("HACK")) MARKER_RE.findAll(raw) else emptySequence()
            for (m in markers) {
                val before = raw.substring(0, m.range.first)
                val owner = spans?.firstOrNull { m.range.first in it.second }?.first
                val rec = linkedMapOf<String, Any?>(
                    // a minified model is one line: "line 1" says nothing there, the path does
                    "file" to label, "line" to (if (minified) null else before.count { it == '\n' } + 1),
                    "marker" to m.groupValues[1], "text" to markerText(raw, m.range.last + 1),
                    "models" to (if (owner != null) listOf("$nodeType:$owner") else markerNodes),
                )
                if (mtype !in XML_MODEL_TYPES) JsonPath.at(raw, m.range.first)?.let { path ->
                    rec["path"] = path
                    val (value, element) = markerSite(jsonDoc, path)
                    value?.let { rec["value"] = it }
                    element?.let { rec["element"] = it }
                }
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
                // one scan of the part for both of its readers below
                val exprMatches = Constants.EXPR_RE.findAll(text).toList()
                for (e in exprMatches.map { Constants.htmlUnescape(it.value) }) {
                    for (k in ks) ctx.exprUse.getOrPut(e) { LinkedHashSet() }.add("$nodeType:$k")
                }
                for (m in Constants.MUSTACHE_RE.findAll(text).map { Constants.htmlUnescape(it.value) }) {
                    for (k in ks) ctx.mustacheUse.getOrPut(m) { LinkedHashSet() }.add("$nodeType:$k")
                }
                if (parser != null) {
                    // Make ${bean.method()} references in this model visible (model → bean, labelled).
                    val calls = LinkedHashSet<Pair<String, String>>()
                    for (em in exprMatches) {
                        // a call spelled inside a string literal of the expression is text, not a call
                        for (cm in Constants.METHOD_CALL_FULL_RE.findAll(STRING_LITERAL_RE.replace(Constants.htmlUnescape(em.value), " "))) {
                            val b = cm.groupValues[1]
                            val meth = cm.groupValues[2]
                            if (b !in Constants.FLOWABLE_CONTEXT && b !in Constants.JAVA_LITERALS) calls.add(b to meth)
                        }
                    }
                    for (k in ks) for ((b, meth) in calls) ctx.addRef(k, nodeType, label, "calls $meth()", "bean", b)
                }
                VarHarvest.collectDeclaredVars(ctx, text, ks)
                VarHarvest.collectDirectedVars(ctx, text, ks)
                // `templateService….variableContainer(execution)…process()` renders a template against the
                // whole scope: every variable the model holds may be read, as by `getVariables()`.
                if (Constants.WHOLE_CONTAINER_RE.containsMatchIn(text)) for (k in ks) ctx.varScopeReadsAll.add("$nodeType:$k")
                // A Spring property the model reads: correlated by its key, like a signal by its name. Only a
                // part that calls `getProperty` at all is unescaped and scanned — nearly none does.
                if (text.contains("getProperty")) for (pm in Constants.PROPERTY_READ_RE.findAll(Constants.htmlUnescape(text))) {
                    for (k in ks) ctx.addRef(k, nodeType, label, "reads-property", "property", pm.groupValues[1])
                }
            }

            if (nodeType in FREEMARKER_TYPES) {
                val locals = LinkedHashSet<String>()
                for (m in FTL_LOCAL_RE.findAll(raw)) {
                    val names = m.groups[1]?.value ?: m.groups[2]?.value ?: m.groups[3]?.value ?: ""
                    names.split(',', ' ', '\t', '\n').map { it.trim() }.filter { IDENT_RE.matches(it) }.forEach { locals.add(it) }
                }
                if (locals.isNotEmpty()) for (k in mkeys.filterNotNull()) ctx.freemarkerLocals.getOrPut("$nodeType:$k") { LinkedHashSet() }.addAll(locals)
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
            // Where copies of one model disagree on what they reference, the node shows the references of
            // every copy while naming the first file only — so the node says so (`otherCopies`) instead of
            // presenting another copy's links as the first one's. Copies that reference the same models
            // (a Design export and its deployment) are the same model, whatever their format.
            // Compared only within one format: a Design export and a deployment file are read by different
            // readers, and a reference one of them does not reach is no difference between the copies.
            val format = if (label.substringAfterLast('!').substringAfterLast('/').endsWith(".json")) "design" else "file"
            // A DRD's decisions are left out: `decisionElement_1` in two DRDs is two services' decisions, not
            // two copies of one model, and a copy of a table merges into the table (DecisionServiceCopies).
            for (k in mkeys.filterNotNull().filter { it !in drdKeys }.map { it.toString() }) {
                val id = nodeType to "$k|$format"
                val sig = ctx.refs.subList(refsBefore, ctx.refs.size).asSequence()
                    .filter { it["from"] == k }.map { "${it["rel"]}|${it["kind"]}|${it["value"]}" }.toSortedSet().joinToString(";")
                val (prevLabel, prev) = copyRefs.putIfAbsent(id, label to sig) ?: continue
                val first = copies[nodeType to k] ?: continue
                if (prev == sig || prevLabel == label) continue
                @Suppress("UNCHECKED_CAST")
                (first.second as? MutableMap<String, Any?>)?.let { m ->
                    val others = (m["otherCopies"] as? MutableList<String>) ?: ArrayList<String>().also { m["otherCopies"] = it }
                    if (label !in others) others.add(label)
                }
                diag("copy", label, "'$k' ($nodeType) is also defined in $prevLabel, and the two reference different " +
                    "models — one model is shown, with the references of both copies")
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
                // A decision service's twin is the only place an older export says which table each
                // decision of its DRD is a copy of.
                if (folder == "decision-service-models") DecisionServiceCopies.readDesignTwin(ctx, key, body)
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
                // The legacy action wrapper says `form`, the modern parser reads `formKey`. Design writes it as
                // `{id, key}` (36 of 38 real exports), so reading only a string lost the form of every action
                // whose Design export was its only copy — and reported the form as used by nothing.
                if (mtype == "action" && doc["formKey"] == null) {
                    (ModelParsers.modelRefKey(doc["form"]) as? String)?.takeIf { truthyStr(it) }?.let { doc["formKey"] = it }
                }
            }
            dispatch(mtype, com.flowable.atlas.model.MiniJson.stringify(doc).toByteArray(Charsets.UTF_8), label)
        }

        val discovered = Discovery.discover(root)
        val isDir = root.isDirectory
        fun relOf(f: File): String = if (isDir) relativize(root, f) else f.name
        // Said, not silent: a team that links a shared models folder in from a sibling checkout has to be
        // able to see why those models are missing from the report.
        for (link in discovered.outsideLinks) {
            diag("skip", relOf(link), "a symbolic link out of the project — not followed")
        }
        // A `skip`, so a warning: a root-owned database volume inside a checkout is common, and turning that
        // into an error would fail every `--fail-on error` pipeline over a folder that holds no models.
        // Not silent, though — models may have been under it, and a dropped folder looked like an empty one.
        for (dir in discovered.unreadable) {
            diag("skip", relOf(dir).ifEmpty { "." }, "directory could not be listed (permissions) — nothing under it was read")
        }

        fun tooLarge(label: String, size: Long): Boolean {
            if (size <= MAX_MODEL_BYTES) return false
            diag("skip", label, "${size shr 20} MB is above the ${MAX_MODEL_BYTES shr 20} MB limit for one model file — not read")
            return true
        }

        // A model under a test source set is read after every deployed one, loose or archived. The first
        // copy of a (type, key) is the one the graph shows — `copies`, the bucket dedupe and the node all keep
        // it — and discovery lists loose files before archives, so an empty `src/test` fixture sharing a key
        // with the process the app's `.bar` deploys used to stand in for it: the guide then said the main
        // process had no user task. Test copies still count (they become `otherCopies`), they just never win.
        val (testModels, mainModels) = discovered.models.partition { ModelPaths.isTestSourceModel(relOf(it)) }
        val (testArchives, mainArchives) = discovered.archives.partition { ModelPaths.isTestSourceModel(relOf(it)) }

        fun readLoose(models: List<File>) {
            for (path in models) {
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
        }
        readLoose(mainModels)

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

        // Changelog and template text kept so far — see MAX_RETAINED_TEXT_BYTES. What would go past the budget
        // is not kept and is said, like an oversized model: a partial Liquibase picture is better than none.
        var retained = 0L
        fun retain(label: String, size: Long): Boolean {
            if (retained + size <= retainedTextBudget) { retained += size; return true }
            diag("skip", label, "the ${retainedTextBudget shr 20} MB of changelog and template text one run keeps is used up — not read")
            return false
        }

        fun scanEntry(entryName: String, size: Long, label: String, depth: Int, read: () -> ByteArray) {
            val base = entryName.substringAfterLast('/')
            val mt = ModelKinds.modelTypeFor(base)
            val isArchive = com.flowable.atlas.model.ModelPaths.isArchive(base)
            val isJson = base.lowercase().endsWith(".json")
            val low = base.lowercase()
            if (Discovery.isTemplatePart(low)) {
                if (size in 0..MAX_MODEL_BYTES || size < 0) {
                    val bytes = read()
                    if (bytes.size <= MAX_MODEL_BYTES && retain(label, bytes.size.toLong())) {
                        templateParts.add(label to String(bytes, Charsets.UTF_8))
                    }
                }
                return
            }
            if (mt == null && !isArchive && (low.endsWith(".xml") || low.endsWith(".sql"))) {
                // a changelog candidate. An oversized resource is not a model that failed, so it is left alone
                // without a word; an XML file that is no changelog is dropped as soon as it is read — the
                // replay would only have discarded it at the end, after holding every one of them. A plain
                // `.sql` is kept: a changelog's `<sqlFile>` may run it.
                if (size in 0..MAX_MODEL_BYTES || size < 0) {
                    val bytes = read()
                    if (bytes.size <= MAX_MODEL_BYTES) {
                        val text = String(bytes, Charsets.UTF_8)
                        if ((low.endsWith(".sql") || LiquibaseCoverage.isChangelog(text)) && retain(label, bytes.size.toLong())) {
                            archiveChangelogs.add(label to text)
                        }
                    }
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

        fun readArchives(archives: List<File>) {
            for (arc in archives) {
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
        }
        readArchives(mainArchives)
        readLoose(testModels)
        readArchives(testArchives)

        for (f in discovered.templateParts) {
            if (tooLarge(relOf(f), f.length()) || !retain(relOf(f), f.length())) continue
            try { templateParts.add(relOf(f) to f.readText(Charsets.UTF_8)) } catch (e: Exception) { diag("parse", relOf(f), e.message ?: e.toString()) }
        }
        TemplateParts.attach(result, ctx, templateParts) { kind, path, msg -> diag(kind, path, msg) }
        // Every DRD and every decision service's twin is read now: a copy of a table merges into the table.
        DecisionServiceCopies.apply(result, ctx, modelIndex, byKey)

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
            // as inside an archive: an XML file that is no changelog is not held, and what is held counts
            if (f.name.lowercase().endsWith(".xml") && !LiquibaseCoverage.isChangelog(txt)) return@mapNotNull null
            if (!retain(relOf(f), f.length())) return@mapNotNull null
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
        attachPropertyDefinitions(result, discovered.configs, ::relOf)
        IdentitySetup.apply(result, discovered.identity, ::relOf) { kind, path, msg -> diag(kind, path, msg) }
        TestDeployments.apply(result, discovered.testSources, ::relOf)

        // Mirror Python's `result.update({... "customFunctions": {...} if custom else None ...})`:
        // ReferenceResolver already set this key to null; overwrite it with the summary shape when
        // custom functions were found (namespace member lists sorted, like Python's dict comprehension).
        if (custom != null) {
            result["customFunctions"] = linkedMapOf<String, Any?>(
                "namespaces" to custom.namespaces.mapValues { it.value.sorted() },
                "flw" to custom.flw.sorted(),
                "topLevel" to custom.topLevel.sorted(),
                "sources" to custom.sources,
                "diagnostics" to custom.diagnostics,
                "signatures" to custom.signatures,
                "summary" to custom.summary(),
            ).apply {
                // the literal values beside them (`$isDesktop: true`) — read, never called, so no function
                if (custom.constants.isNotEmpty()) put("constants", custom.constants.sorted())
            }
        }

        // Health findings, derived from everything above (graph + buckets + diagnostics + custom fns),
        // so every renderer can state what is wrong instead of pointing at the explorer's Checks tab.
        checkCanceled()
        Findings.apply(result, waivers)
        return result
    }

    /**
     * Where each property a model reads is set: `file:line` in the project's Spring configuration, every
     * profile. Matched in Spring's relaxed form (`crm.base-url` = `crm.baseUrl` = `crm.base_url`). An
     * empty list is not a defect — the value can come from the environment, a Helm chart or a vault.
     */
    private fun attachPropertyDefinitions(result: Map<String, Any?>, configs: List<File>, relOf: (File) -> String) {
        val nodes = ((result["graph"] as? Map<*, *>)?.get("nodes") as? List<*>)
            ?.mapNotNull { it as? MutableMap<String, Any?> }?.filter { it["type"] == "property" }.orEmpty()
        if (nodes.isEmpty()) return
        fun relaxed(k: String) = k.lowercase().replace("-", "").replace("_", "")
        val defined = LinkedHashMap<String, MutableList<String>>()
        for (f in configs) {
            if (f.length() > MAX_MODEL_BYTES) continue
            val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            val rel = relOf(f)
            for ((k, line) in SpringProperties.keys(text, f.name)) defined.getOrPut(relaxed(k)) { ArrayList() }.add("$rel:$line")
        }
        for (n in nodes) {
            val data = n["data"] as? MutableMap<String, Any?> ?: continue
            data["definedIn"] = defined[relaxed(n["key"].toString())].orEmpty()
        }
    }

    private fun relativize(root: File, file: File): String =
        root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')

    private fun truthyStr(v: Any?): Boolean = (v as? String)?.isNotEmpty() == true
}
