package com.flowable.atlas.render

import com.flowable.atlas.AtlasBuildInfo
import com.flowable.atlas.model.Dyn
import java.io.File

/**
 * `<project>.erd.html` — the ER diagram designer, a page of its own beside the explorer.
 *
 * It was a page inside the explorer (0.28.0), and that was the wrong place for it: the explorer carries the
 * whole graph and on a large project it outgrows what a Remote Development client will open, while the
 * designer needs a sliver of it. This page carries only what the designer reads — every Liquibase
 * changelog's replayed columns, the database services and their column mappings, the data objects that
 * read a table and their relations — plus the project's own `*.atlas-erd.json` diagrams ([ErdDiagramFiles]).
 * A few hundred KB, whatever the size of the project, and it can be sent to someone who only wants the
 * data model.
 *
 * The page borrows the explorer's stylesheet for its tokens, fonts and controls (one source for how Atlas
 * looks) and runs its own script, `frontend/erd.js`; nothing of `explorer.js` is in it.
 */
object ErdHtmlRenderer {

    /**
     * The page for [result]. [explorerFile] is the explorer written beside it (a file name, relative to the
     * page), so the tables' changelogs, services and data objects link to their explorer pages; null when
     * the page is written on its own.
     */
    fun render(
        result: Map<String, Any?>,
        root: File,
        version: String = AtlasBuildInfo.VERSION,
        generatedAt: java.time.Instant = java.time.Instant.now(),
        explorerFile: String? = null,
    ): String {
        val graph = Dyn.mapOrNull(result["graph"])
            ?: error("result[\"graph\"] is missing or not a map — Atlas.extract must produce it")
        val payload = LinkedHashMap<String, Any?>()
        payload["project"] = root.absoluteFile.name.ifEmpty { "project" }
        payload["generatedAt"] = generatedAt.toString()
        payload["atlasVersion"] = version
        payload["explorer"] = explorerFile
        payload["nodes"] = nodes(graph["nodes"])
        payload["edges"] = (graph["edges"] as? List<*>).orEmpty().mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            if (m["rel"] == "relates-to-service") linkedMapOf("s" to m["s"], "t" to m["t"], "rel" to m["rel"]) else null
        }
        payload["diagrams"] = ErdDiagramFiles.find(root)
        return composeTemplate()
            .replace("__ATLAS_VERSION__", "Atlas $version")
            .replace("__ATLAS_DATA__", ExplorerHtmlRenderer.dataIsland(payload))
    }

    /** Whether the project defines a table the designer can show — `--all` writes the page only then. */
    fun hasTables(result: Map<String, Any?>): Boolean {
        val nodes = Dyn.mapOrNull(result["graph"])?.get("nodes") as? List<*> ?: return false
        return nodes.any { n ->
            val m = n as? Map<*, *> ?: return@any false
            val d = m["data"] as? Map<*, *> ?: return@any false
            when (m["type"]) {
                "liquibase" -> (d["columns"] as? List<*>).orEmpty().isNotEmpty()
                "service" -> !(d["tableName"] as? String).isNullOrBlank()
                else -> false
            }
        }
    }

    /**
     * The nodes the designer's catalog and proposals read, each cut down to the fields they use: a
     * changelog's columns (name, type, table, key) and authority, a database service's table and column
     * mappings, a table-backed data object's name and fields. Everything else in the graph stays in the
     * explorer.
     */
    private fun nodes(all: Any?): List<Map<String, Any?>> =
        (all as? List<*>).orEmpty().mapNotNull { n ->
            val m = n as? Map<*, *> ?: return@mapNotNull null
            val d = m["data"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val data: Map<String, Any?> = when (m["type"]) {
                "liquibase" -> {
                    val cols = (d["columns"] as? List<*>).orEmpty().mapNotNull { c ->
                        val cm = c as? Map<*, *> ?: return@mapNotNull null
                        linkedMapOf<String, Any?>("name" to cm["name"], "type" to cm["type"], "table" to cm["table"])
                            .also { if (cm["pk"] == true) it["pk"] = true }
                    }
                    if (cols.isEmpty()) return@mapNotNull null
                    val status = (d["authority"] as? Map<*, *>)?.get("status")
                    linkedMapOf("columns" to cols, "authority" to linkedMapOf("status" to status))
                }
                "service" -> {
                    val table = d["tableName"] as? String
                    if (table.isNullOrBlank()) return@mapNotNull null
                    linkedMapOf("tableName" to table, "columns" to (d["columns"] as? List<*>).orEmpty().mapNotNull { c ->
                        val cm = c as? Map<*, *> ?: return@mapNotNull null
                        linkedMapOf("name" to cm["name"], "columnName" to cm["columnName"], "type" to cm["type"])
                    })
                }
                "dataObject" -> {
                    val table = d["serviceTableName"] as? String
                    if (table.isNullOrBlank()) return@mapNotNull null
                    linkedMapOf("name" to d["name"], "serviceTableName" to table, "columns" to (d["columns"] as? List<*>).orEmpty().mapNotNull { c ->
                        val cm = c as? Map<*, *> ?: return@mapNotNull null
                        linkedMapOf<String, Any?>("name" to cm["name"], "label" to cm["label"], "type" to cm["type"]).also {
                            cm["refDataObject"]?.let { r -> it["refDataObject"] = r; it["relationship"] = cm["relationship"] }
                        }
                    })
                }
                else -> return@mapNotNull null
            }
            linkedMapOf("id" to m["id"], "type" to m["type"], "key" to m["key"], "label" to m["label"], "file" to m["file"], "data" to data)
        }

    private fun composeTemplate(): String {
        var t = ExplorerHtmlRenderer.asset("erd.html")
        // The designer's own script and styles first, while the template is still only the template: nothing
        // inlined afterwards can be mistaken for a marker.
        t = t.replace("/*__ERD_JS__*/", ExplorerHtmlRenderer.asset("erd.js"))
        t = t.replace("/*__ERD_CSS__*/", ExplorerHtmlRenderer.asset("erd.css"))
        t = t.replace("/*__ATLAS_CSS__*/", ExplorerHtmlRenderer.asset("explorer.css"))
        return t.trimEnd('\n')
    }
}
