package com.flowable.atlas.render

/**
 * One node with its full context, in both directions — the artifact tier that was missing.
 *
 * Atlas offered a ~3 KB summary, a report that reaches hundreds of KB on a real project, and a graph that
 * reaches megabytes. An agent asked to change one process had nothing sized for that: the summary does
 * not mention it, the report buries it, and the graph cannot be read whole. So it read too much or
 * guessed.
 *
 * A slice answers "tell me about `process:orderProcess`" in a page: what it is, where it lives, what it
 * uses, **who uses it** (the direction the report never renders), and which findings touch it.
 */
object SliceRenderer {

    /**
     * @param target `"<type>:<key>"`, or just a key — a bare key matches any node type, since a reader
     *   asking about `orderProcess` should not have to know Atlas calls it a `process`.
     */
    @Suppress("UNCHECKED_CAST")
    fun render(result: Map<String, Any?>, target: String): String? {
        val graph = result["graph"] as? Map<String, Any?> ?: return null
        val nodes = graph["nodes"] as? List<Map<String, Any?>> ?: return null
        val edges = graph["edges"] as? List<Map<String, Any?>> ?: emptyList()

        val matches = nodes.filter { n ->
            n["id"] == target || (!target.contains(':') && n["key"] == target)
        }
        if (matches.isEmpty()) return null
        val L = ArrayList<String>()
        if (matches.size > 1) {
            L.add("_${matches.size} nodes match `$target`: " +
                matches.joinToString(", ") { "`${it["id"]}`" } + "_\n")
        }
        for (node in matches) {
            renderOne(node, nodes, edges, result, L)
            L.add("")
        }
        return L.joinToString("\n").trimEnd('\n')
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderOne(
        node: Map<String, Any?>,
        nodes: List<Map<String, Any?>>,
        edges: List<Map<String, Any?>>,
        result: Map<String, Any?>,
        L: MutableList<String>,
    ) {
        val id = node["id"] as? String ?: return
        val byId = nodes.associateBy { it["id"] }
        fun describe(nid: Any?): String {
            val n = byId[nid] ?: return "`$nid`"
            val label = n["label"]?.toString()
            val key = n["key"]?.toString()
            val name = if (label != null && label != key) " $label" else ""
            return "`$nid`$name"
        }

        L.add("# `$id`" + (node["label"]?.toString()?.takeIf { it != node["key"] }?.let { " — $it" } ?: ""))
        val where = Fmt.fields("type" to Fmt.list(node["type"]), "key" to Fmt.codeList(node["key"]),
            "file" to Fmt.codeList(node["file"]))
        if (where.isNotEmpty()) L.add("_${where}_\n")

        // Outgoing: what this node uses.
        val out = edges.filter { it["s"] == id }.groupBy { it["rel"]?.toString() ?: "?" }
        if (out.isNotEmpty()) {
            L.add("## Uses")
            for ((rel, group) in out.entries.sortedBy { it.key }) {
                L.add("- **$rel** → " + Fmt.cap(group.map { describe(it["t"]) }, 20))
            }
            L.add("")
        }
        // Incoming: who uses this node. This is the half a reader cannot get from the report at all.
        val inc = edges.filter { it["t"] == id }.groupBy { it["rel"]?.toString() ?: "?" }
        if (inc.isNotEmpty()) {
            L.add("## Used by")
            for ((rel, group) in inc.entries.sortedBy { it.key }) {
                L.add("- **$rel** ← " + Fmt.cap(group.map { describe(it["s"]) }, 20))
            }
            L.add("")
        }
        if (out.isEmpty() && inc.isEmpty()) L.add("_Nothing references this node, and it references nothing._\n")

        // Findings that name this node — the reason it may be misbehaving.
        val findings = (result["findings"] as? List<Map<String, Any?>> ?: emptyList())
            .filter { it["node"] == id }
        if (findings.isNotEmpty()) {
            L.add("## Findings (${findings.size})")
            for (f in findings.take(20)) {
                val mark = if (f["severity"] == "error") "⚠" else "·"
                val at = f["element"]?.toString()?.let { " at `$it`" } ?: ""
                L.add("- $mark ${f["message"]}$at")
            }
            if (findings.size > 20) L.add("- … (+${findings.size - 20} more)")
            L.add("")
        }

        // The node's own data, minus the containers already covered above.
        val data = node["data"] as? Map<String, Any?> ?: emptyMap()
        val scalars = data.entries.filter { (k, v) ->
            k != "_uses" && k != "usedBy" && (v == null || v is String || v is Number || v is Boolean)
        }.filter { Fmt.list(it.value).isNotEmpty() }
        if (scalars.isNotEmpty()) {
            L.add("## Attributes")
            for ((k, v) in scalars) L.add("- **$k**: ${Fmt.list(v)}")
            L.add("")
        }
        val containers = data.keys.filter { k ->
            k != "_uses" && data[k].let { it is Collection<*> && it.isNotEmpty() || it is Map<*, *> && it.isNotEmpty() }
        }
        if (containers.isNotEmpty()) {
            L.add("_More on this node in the graph: " + containers.sorted().joinToString(", ") { "`$it`" } +
                "._")
        }
    }
}
