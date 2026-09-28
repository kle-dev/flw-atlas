package com.flowable.atlas.render

import com.flowable.atlas.model.Dyn
import com.flowable.atlas.AtlasBuildInfo
import com.flowable.atlas.diagram.ModelBytes
import com.flowable.atlas.diagram.ModelPicture
import com.flowable.atlas.diagram.Picture
import com.flowable.atlas.graph.CheckCatalog
import com.flowable.atlas.graph.UnusedVariables
import com.flowable.atlas.model.MiniJson
import java.io.File

/**
 * The self-contained, offline interactive HTML explorer.
 *
 * Port of `flowable_atlas.py` `html_render` + `_compose_template` + `_frontend_asset`. Instead of
 * Python's `repr()`-embedded `_EMBEDDED_FRONTEND`, the three frontend files are the editable source
 * in `:core` resources (`core/src/main/resources/frontend/explorer.{html,css,js}`) and read at
 * runtime via the classloader. Composition matches Python exactly: inline the CSS into the
 * `/*__ATLAS_CSS__*/` marker, the JS into `/*__ATLAS_JS__*/`, `rstrip` trailing newlines, then
 * substitute the graph-JSON island into `__ATLAS_DATA__`.
 */
object ExplorerHtmlRenderer {

    fun render(
        result: Map<String, Any?>,
        root: File,
        version: String = AtlasBuildInfo.VERSION,
        generatedAt: java.time.Instant = java.time.Instant.now(),
        /** Who is accepting findings from this page — the default `by` of a rule written here. */
        waiverAuthor: String? = null,
        /** The island size (chars) above which it is embedded deflated — see [islandTag]. */
        compressAbove: Int = compressAboveDefault(),
    ): String {
        // error() rather than an empty map: every caller passes an Atlas.extract result, which always
        // carries "graph". The previous `as Map` threw here too — an explorer page silently rendered
        // with no graph would be worse than a stack trace.
        val graph = Dyn.mapOrNull(result["graph"])
            ?: error("result[\"graph\"] is missing or not a map — Atlas.extract must produce it")
        // Same payload object html_render builds, in the same key order.
        val payload = LinkedHashMap<String, Any?>()
        payload["project"] = root.absoluteFile.name.ifEmpty { "project" }
        // Provenance: a page mailed to a reviewer could be a day or six months old and could not say.
        payload["generatedAt"] = generatedAt.toString()
        payload["atlasVersion"] = version
        payload["stats"] = result["stats"]
        // Health counts come from :core (Findings.kt) so the explorer, the Markdown artifacts and the
        // CLI status line cannot disagree about how many findings a project has.
        payload["checks"] = result["checks"] ?: LinkedHashMap<String, Any?>()
        // What each check is, why it matters and what to do — from the one catalog every surface reads,
        // so the page can explain a finding without carrying a second copy of the explanation.
        payload["checkCatalog"] = CheckCatalog.payload()
        // What the unused-variable check refuses to conclude, in its own words. The report states these
        // next to its findings, and they travel from the one place that implements them so the page can
        // never claim more confidence than the check actually has.
        payload["silenceRules"] = UnusedVariables.SILENCE_RULES
        // The waiver file as :core read it — every rule with its author, date, expiry and how many
        // findings it matched, the notes, and what is wrong with the file. The page edits this and
        // writes it back, so it must receive all of it or it would drop what it did not see.
        // Absent when no waiver file was read.
        result["waivers"]?.let { payload["waivers"] = it }
        // The findings themselves, open and accepted alike. The page used to rebuild its rows from
        // the live nodes and receive only a node -> check-id index, which left it re-deriving a
        // judgement :core had already made — and disagreeing with it the moment anything was
        // waived. A finding is a few hundred bytes; a thousand of them are a fraction of the page.
        payload["findings"] = findingsForPage(result)
        // Who is accepting, so a rule written from this page can say so.
        payload["waiverAuthor"] = waiverAuthor ?: ""
        payload["nodes"] = attachDiagrams(slimNodes(graph["nodes"]), root)
        payload["edges"] = graph["edges"]
        val data = dataIsland(payload)
        // Stamp the version before the data island so a version like "__ATLAS_VERSION__" can't collide
        // with anything inside the (already-built) JSON.
        val page = composeTemplate()
        check(PLAIN_ISLAND in page) { "explorer.html has lost its data island: $PLAIN_ISLAND" }
        return page
            .replace("__ATLAS_VERSION__", "Atlas $version")
            .replace(PLAIN_ISLAND, islandTag(data, compressAbove))
    }

    /** The island as explorer.html writes it, with the placeholder the payload replaces. */
    private const val PLAIN_ISLAND = """<script type="application/json" id="atlas-data">__ATLAS_DATA__</script>"""

    /**
     * An island larger than this is embedded deflated. 1 MB keeps a fixture's or a small project's page
     * plain JSON, readable in its source, while every project large enough to matter shrinks.
     */
    const val COMPRESS_ABOVE = 1 shl 20

    /**
     * [COMPRESS_ABOVE], or the `atlas.explorer.compressAbove` system property: how the build gets a deflated
     * page out of a fixture far below 1 MB, to run every browser check on it again (`:cli:explorerUiTestDeflated`).
     */
    internal fun compressAboveDefault(): Int =
        System.getProperty("atlas.explorer.compressAbove")?.toIntOrNull() ?: COMPRESS_ABOVE

    /**
     * The `<script id="atlas-data">` element for [json]: as it is up to [compressAbove] chars, and above
     * that raw DEFLATE, Base64-encoded, with the inflated byte count — which `atlasIslandText` in
     * explorer.js reverses before the page boots.
     *
     * Why: an explorer is mostly its island, and the island is mostly repetition (the same keys, types and
     * file paths on every node and every Liquibase column), so it deflates about tenfold. A JetBrains
     * Remote Development client refuses to open any file above the IDE's content limit (20 MB,
     * `idea.max.content.load.filesize`) before a plugin's editor is even asked — and a large project passed
     * it once 0.28.0 carried every Liquibase column's origin, every change set and each service's schema
     * coverage (the Liquibase part of an island about tripled on the projects measured).
     * Deflated, the same page is a few MB, and still one self-contained file. Raw DEFLATE rather than gzip:
     * the page inflates it with its own code (no browser API does it synchronously), and a gzip header
     * and checksum would only be more to parse; the byte count is what catches a truncated file.
     */
    internal fun islandTag(json: String, compressAbove: Int = COMPRESS_ABOVE): String {
        if (json.length <= compressAbove) return PLAIN_ISLAND.replace("__ATLAS_DATA__", json)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
        val deflated = try {
            val out = java.io.ByteArrayOutputStream(bytes.size / 8)
            java.util.zip.DeflaterOutputStream(out, deflater, 1 shl 16).use { it.write(bytes) }
            out.toByteArray()
        } finally {
            deflater.end()
        }
        return """<script type="application/octet-stream" id="atlas-data" data-encoding="deflate-base64" data-size="${bytes.size}">""" +
            java.util.Base64.getEncoder().encodeToString(deflated) + "</script>"
    }

    /**
     * The payload as the text of the `<script type="application/json">` island. Every `<` goes out as
     * `<`, not just `</`: a string holding `<!--<script>` (a commented-out tag in an htmlComponent)
     * puts the HTML tokenizer into its double-escaped state, where the real `</script>` no longer closes
     * the island. `<` only ever occurs inside JSON strings, where the escape reads back as the same character.
     */
    internal fun dataIsland(payload: Any?): String = MiniJson.stringify(payload).replace("<", "\\u003c")

    /**
     * Project each node's `data` down to what the frontend actually reads before embedding it.
     *
     * [GraphBuilder][com.flowable.atlas.graph.GraphBuilder] stashes the *whole* parsed model object in
     * a model node's `data` (`addNode(type, key, name, file, o)`), but `explorer.js` only reads a fixed
     * set of fields ([FRONTEND_DATA_KEYS]) plus, in its generic "remaining fields" dump, every scalar.
     * The unread remainder — chiefly the full element/diagram tree of each model — is dead weight that
     * can bloat the embedded island to tens of MB. That is harmless in a desktop browser (a 15 MB page
     * parses in ~0.3 s) but pathological for the in-IDE JCEF viewer and for any AV-scanned `file://`
     * read on Windows, where it turns into minutes.
     *
     * Rule: keep an entry if its value is a scalar (so the generic dump is byte-for-byte unchanged) or
     * its key is consumed by name. Fresh maps are built so the shared graph — reused by the other
     * renderers in the same in-process run — is left untouched.
     */
    private fun slimNodes(nodes: Any?): Any? {
        val list = nodes as? List<*> ?: return nodes
        return list.map { node ->
            val nm = node as? Map<*, *> ?: return@map node
            val out = LinkedHashMap<Any?, Any?>(nm) // shallow copy keeps insertion order (id, type, …)
            (nm["data"] as? Map<*, *>)?.let { out["data"] = slimData(it) }
            out
        }
    }

    /**
     * How much wireframe SVG an explorer page carries. Every form and page of a project is drawn, and a
     * project with three hundred forms would otherwise double the page — which the Remote Development
     * stub stops caching past four million characters. Deterministic: the smallest wireframes go in first
     * (node id breaks ties) until [total] is used; one larger than [perPicture] never does. A form left
     * out says so on its page, and the IDE preview and the diagrams folder still draw it.
     */
    internal data class WireframeBudget(val perPicture: Int = 64_000, val total: Int = 1_500_000)

    /**
     * Attach each model node's picture to its (already-slimmed) `data`: a process, case or decision its
     * diagram under `diagram`, a form or page its wireframe, with `diagramKind` saying which. A decision
     * drawn only as a table is left out — its page renders the rules as HTML. Runs *after* [slimNodes] on
     * the fresh payload maps — the shared graph and the `extract()` result are never touched, and a project
     * without anything to draw adds nothing.
     */
    internal fun attachDiagrams(nodes: Any?, root: File, budget: WireframeBudget = WireframeBudget()): Any? {
        val list = nodes as? List<*> ?: return nodes
        val wireframes = ArrayList<Triple<String, MutableMap<Any?, Any?>, String>>()
        val subforms = ModelPicture.subformsOf(list, root)
        for (nodeAny in list) {
            val node = Dyn.anyMutableMapOrNull(nodeAny) ?: continue
            val type = ModelPicture.typeOfNode(node["type"] as? String) ?: continue
            val file = node["file"] as? String ?: continue
            val data = Dyn.anyMutableMapOrNull(node["data"]) ?: continue
            // Resolve via ModelBytes (handles loose files AND "<archive>!<entry>" labels) — a plain
            // File(root, file) silently fails for models packaged inside a .zip/.bar/Design export.
            // A model that has no layout gets no diagram and nothing else; a model whose diagram could
            // not be produced says so on its page (`diagramError` lands in Other attributes), because
            // "no diagram" and "the diagram failed" used to look the same.
            val resolved = ModelBytes.resolve(root, file)
            if (resolved == null) { data["diagramError"] = "model source could not be read from $file"; continue }
            val (bytes, name) = resolved
            val pic = runCatching { ModelPicture.render(bytes, name, type, subforms) }
                .onFailure { data["diagramError"] = "diagram could not be rendered: ${it.message ?: it.javaClass.simpleName}" }
                .getOrNull() ?: continue
            when (pic.kind) {
                Picture.Kind.DIAGRAM -> { data["diagram"] = pic.svg; data["diagramKind"] = pic.kind.id }
                Picture.Kind.WIREFRAME -> wireframes.add(Triple(node["id"]?.toString() ?: "", data, pic.svg))
                Picture.Kind.DECISION_TABLE -> {}
            }
        }
        var used = 0
        for ((_, data, svg) in wireframes.sortedWith(compareBy({ it.third.length }, { it.first }))) {
            if (svg.length <= budget.perPicture && used + svg.length <= budget.total) {
                data["diagram"] = svg
                data["diagramKind"] = Picture.Kind.WIREFRAME.id
                used += svg.length
            } else {
                data["diagramOmitted"] = "The wireframe (${(svg.length + 1023) / 1024} KB) is not embedded — over this page's " +
                    "budget for drawings. The IDE's model preview and the generated diagrams folder draw it."
            }
        }
        return list
    }

    private fun slimData(data: Map<*, *>): Map<Any?, Any?> {
        val out = LinkedHashMap<Any?, Any?>()
        for ((k, v) in data) {
            if (v == null || v is String || v is Boolean || v is Number || k in FRONTEND_DATA_KEYS) out[k] = v
        }
        return out
    }

    /**
     * Container `node.data` keys deliberately kept OUT of the explorer payload, each with its reason.
     * [PayloadCompletenessTest] asserts every container key a parser emits is either allowlisted in
     * [FRONTEND_DATA_KEYS] or consciously listed here — so a new parser key can never be silently
     * invisible again: the build fails until the developer decides.
     */
    internal val STRIPPED_DATA_KEYS: Set<String> = setOf(
        "childModels", // redundant: parseApp records a `contains` ref per child, rendered as edges
        // reverse artifact map for graph.json/overview.md; the explorer rebuilds it from the artifact
        // nodes' `usedBy` lists (`usesIndex()` in explorer.js), of which it is the exact transpose
        "_uses",
    )

    /**
     * The `node.data` keys `explorer.js` consumes. Scalars survive regardless (see [slimData]), so this
     * only has to enumerate the *container* fields the detail view reads; extra names are harmless.
     * Derived from every `d.<field>` / `n.data.<field>` access in `explorer.js` — keep in sync if the
     * frontend starts reading a new nested field ([PayloadCompletenessTest] fails when a parser emits
     * a container key that is neither here nor in [STRIPPED_DATA_KEYS]).
     */
    internal val FRONTEND_DATA_KEYS = setOf(
        "actionPermissions", "aggregations", "definedIn", "memberGroups", "lookupGroups", "usersPerDefinition", "loadedFrom", "deployedByTests", "otherCopies",
        "aiVendor", "annotation", "assignmentActions", "auth", "authority", "baseUrl", "beanNames",
        "bindings", "botKey",
        "callActivities", "completionActions",
        "calledMethods", "calls", "candidateStarterGroups", "channels", "channelType", "class", "className",
        "changeSets", "columns", "conditions", "dropped", "droppedTables", "includesMissing", "revisions",
        "controller", "correlation", "coverage", "crossedColumns", "dataObjects", "dataObjectType",
        "dataSources",
        "declaredIn", "decisions",
        "destination", "dictionary",
        "documentation", "dynamic", "effectiveTables", "enableApiEndpoint", "endpoints", "escalations",
        "eventKey",
        "eventListeners", "events", "external",
        "external_url", "extractors", "fields", "flowableApi", "flows", "formKey", "forms", "fullUrl",
        "fullTextVariables", "gateways", "groups", "handler",
        "hitPolicy",
        "http", "initializationActions", "initiatorVariableName", "inputDefs", "inputExpressions",
        "inputs", "interfaces", "ioParameters",
        "ioParams", "kind",
        "knowledgeBase", "lanes", "listeners", "literalSecrets", "member", "message", "method", "methods", "milestones",
        "missingModel", "modelName", "modelRefs", "multiInstance", "name",
        "namespace", "operations", "otherTasks", "outcomes", "outParams", "outputDefs", "outputs",
        "package",
        "pages", "parameters", "params",
        "path", "payload", "permissionGroups", "permissions", "planModel", "platform", "problems",
        "readCount", "reads", "readsUnknown",
        "referencedLiquibaseModelKey", "restCalls", "roles", "route", "ruleCount", "rules", "ruleTasks",
        "schemaCoverage", "scope", "scopes", "scopeUnresolved", "secretFields",
        "scopeType", "scriptProblems", "scriptSites", "scriptTasks", "sentries", "service",
        "serviceTableName", "serviceTasks",
        "signalName", "signature", "sortParameters", "sourceId",
        "sourceIndex", "sources", "subforms", "subProcesses", "tableName", "tables", "temperature",
        "thresholds", "tools", "topics",
        "type", "typeDefs", "types", "unread", "unreadIn", "url", "usages",
        "usedBy", "userTasks", "variables", "variationParameters", "variations", "vectorStore",
        "writeCount", "writes",
    )

    /**
     * The findings as the page needs them: the identity (`check`, `node`, `element`, `subject`), what
     * to show (`severity`, `label`, `message`, `snippet`), where to go (`file`, `line`) and whether a
     * rule already covers it (`waived`). Same keys as graph.json, so nothing is renamed on the way.
     */
    @Suppress("UNCHECKED_CAST")
    private fun findingsForPage(result: Map<String, Any?>): List<Map<String, Any?>> =
        (result["findings"] as? List<Map<String, Any?>> ?: emptyList()).map { f ->
            LinkedHashMap<String, Any?>().also { out ->
                for (k in PAGE_FINDING_KEYS) f[k]?.let { out[k] = it }
            }
        }

    private val PAGE_FINDING_KEYS = listOf(
        "check", "severity", "node", "label", "element", "subject", "file", "line", "snippet", "message", "waived",
    )

    /** The full explorer HTML page (CSS/JS inlined; `__ATLAS_DATA__` still unresolved). */
    private fun composeTemplate(): String {
        var t = asset("explorer.html")
        t = t.replace("/*__ATLAS_CSS__*/", asset("explorer.css"))
        t = t.replace("/*__ATLAS_JS__*/", asset("explorer.js"))
        return t.trimEnd('\n')
    }

    /** A frontend file from `:core`'s resources — the explorer's, or the ER page's ([ErdHtmlRenderer]). */
    internal fun asset(name: String): String {
        val stream = ExplorerHtmlRenderer::class.java.getResourceAsStream("/frontend/$name")
            ?: error("frontend asset /frontend/$name not on the classpath")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
