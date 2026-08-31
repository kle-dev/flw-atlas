package com.flowable.atlas.render

import com.flowable.atlas.model.Dyn
import com.flowable.atlas.AtlasBuildInfo
import com.flowable.atlas.diagram.DiagramRenderer
import com.flowable.atlas.diagram.ModelBytes
import com.flowable.atlas.graph.UnusedVariables
import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.model.ModelType
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

    fun render(result: Map<String, Any?>, root: File, version: String = AtlasBuildInfo.VERSION): String {
        // error() rather than an empty map: every caller passes an Atlas.extract result, which always
        // carries "graph". The previous `as Map` threw here too — an explorer page silently rendered
        // with no graph would be worse than a stack trace.
        val graph = Dyn.mapOrNull(result["graph"])
            ?: error("result[\"graph\"] is missing or not a map — Atlas.extract must produce it")
        // Same payload object html_render builds, in the same key order.
        val payload = LinkedHashMap<String, Any?>()
        payload["project"] = root.absoluteFile.name.ifEmpty { "project" }
        payload["stats"] = result["stats"]
        payload["diagnostics"] = result["diagnostics"] ?: ArrayList<Any?>()
        payload["customFunctions"] = result["customFunctions"]
        // Health counts come from :core (Findings.kt) so the explorer, the Markdown artifacts and the
        // CLI status line cannot disagree about how many findings a project has. The itemized
        // `findings` list stays out of the payload: the Checks tab renders its items from the live
        // nodes, which it needs anyway for chips and links.
        payload["checks"] = result["checks"] ?: LinkedHashMap<String, Any?>()
        // What the unused-variable check refuses to conclude, in its own words. The report states these
        // next to its findings, and they travel from the one place that implements them so the page can
        // never claim more confidence than the check actually has.
        payload["silenceRules"] = UnusedVariables.SILENCE_RULES
        payload["nodes"] = attachDiagrams(slimNodes(graph["nodes"]), root)
        payload["edges"] = graph["edges"]
        // json.dumps(payload, ensure_ascii=False, default=list).replace("</", "<\/")
        val data = MiniJson.stringify(payload).replace("</", "<\\/")
        // Stamp the version before the data island so a version like "__ATLAS_VERSION__" can't collide
        // with anything inside the (already-built) JSON.
        return composeTemplate()
            .replace("__ATLAS_VERSION__", "Atlas $version")
            .replace("__ATLAS_DATA__", data)
    }

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
     * Attach each process/case/decision node's rendered diagram SVG to its (already-slimmed) `data` map
     * under `diagram`, so the explorer can show the diagram inline. Runs *after* [slimNodes] on the
     * fresh payload maps — the shared graph and the `extract()` result are never touched, and a project
     * without any BPMN/CMMN/DMN layout adds nothing (leaving the payload byte-for-byte as before).
     */
    private fun attachDiagrams(nodes: Any?, root: File): Any? {
        val list = nodes as? List<*> ?: return nodes
        for (nodeAny in list) {
            val node = Dyn.anyMutableMapOrNull(nodeAny) ?: continue
            val type = diagramType(node["type"] as? String) ?: continue
            val file = node["file"] as? String ?: continue
            val data = Dyn.anyMutableMapOrNull(node["data"]) ?: continue
            // Resolve via ModelBytes (handles loose files AND "<archive>!<entry>" labels) — a plain
            // File(root, file) silently fails for models packaged inside a .zip/.bar/Design export.
            val (bytes, name) = ModelBytes.resolve(root, file) ?: continue
            val svg = runCatching { DiagramRenderer.renderSvg(bytes, name, type) }.getOrNull() ?: continue
            data["diagram"] = svg
        }
        return list
    }

    private fun diagramType(nodeType: String?): ModelType? = when (nodeType) {
        "process" -> ModelType.PROCESS
        "case" -> ModelType.CASE
        "decision" -> ModelType.DECISION
        else -> null
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
        "_uses", // reverse artifact map for graph.json/overview.md; the explorer derives it from edges
    )

    /**
     * The `node.data` keys `explorer.js` consumes. Scalars survive regardless (see [slimData]), so this
     * only has to enumerate the *container* fields the detail view reads; extra names are harmless.
     * Derived from every `d.<field>` / `n.data.<field>` access in `explorer.js` — keep in sync if the
     * frontend starts reading a new nested field ([PayloadCompletenessTest] fails when a parser emits
     * a container key that is neither here nor in [STRIPPED_DATA_KEYS]).
     */
    internal val FRONTEND_DATA_KEYS = setOf(
        "actionPermissions", "aggregations",
        "aiVendor", "annotation", "assignmentActions", "auth", "authority", "baseUrl", "beanNames",
        "bindings", "botKey",
        "callActivities", "completionActions",
        "calledMethods", "calls", "candidateStarterGroups", "channels", "channelType", "class", "className",
        "columns", "conditions",
        "controller", "correlation", "coverage", "dataObjects", "dataObjectType", "dataSources",
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
        "knowledgeBase", "lanes", "listeners", "member", "message", "method", "methods", "milestones",
        "missingModel", "modelName", "modelRefs", "multiInstance", "name",
        "namespace", "operations", "otherTasks", "outcomes", "outParams", "outputDefs", "outputs",
        "package",
        "pages", "parameters", "params",
        "path", "payload", "permissionGroups", "permissions", "planModel", "platform", "problems",
        "readCount", "reads", "readsUnknown",
        "referencedLiquibaseModelKey", "restCalls", "roles", "route", "ruleCount", "rules", "ruleTasks",
        "schemaCoverage", "scope", "scopes", "scopeUnresolved",
        "scopeType", "scriptProblems", "scriptSites", "scriptTasks", "sentries", "service",
        "serviceTableName", "serviceTasks",
        "signalName", "signature", "sortParameters", "sourceId",
        "sourceIndex", "sources", "subforms", "subProcesses", "tableName", "tables", "temperature",
        "thresholds", "tools", "topics",
        "type", "typeDefs", "types", "unread", "unreadIn", "url", "usages",
        "usedBy", "userTasks", "variables", "variationParameters", "variations", "vectorStore",
        "writeCount", "writes",
    )

    /** The full explorer HTML page (CSS/JS inlined; `__ATLAS_DATA__` still unresolved). */
    private fun composeTemplate(): String {
        var t = asset("explorer.html")
        t = t.replace("/*__ATLAS_CSS__*/", asset("explorer.css"))
        t = t.replace("/*__ATLAS_JS__*/", asset("explorer.js"))
        return t.trimEnd('\n')
    }

    private fun asset(name: String): String {
        val stream = ExplorerHtmlRenderer::class.java.getResourceAsStream("/frontend/$name")
            ?: error("frontend asset /frontend/$name not on the classpath")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
