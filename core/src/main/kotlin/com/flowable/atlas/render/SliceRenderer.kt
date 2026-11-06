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
     *   asking about `orderProcess` should not have to know Atlas calls it a `process`. When neither
     *   matches exactly, the forms a reader actually has in hand are tried, in order: the id or key in
     *   another case, a Java class's simple name (what a `delegateExpression` shows), the model's file
     *   path (what an agent editing it knows first) and the label. Each miss used to cost a full
     *   re-analysis to learn the one spelling Atlas accepted.
     * @return null when nothing matches — [suggest] then names the nearest ids.
     */
    @Suppress("UNCHECKED_CAST")
    fun render(result: Map<String, Any?>, target: String): String? {
        val graph = result["graph"] as? Map<String, Any?> ?: return null
        val nodes = graph["nodes"] as? List<Map<String, Any?>> ?: return null
        val edges = graph["edges"] as? List<Map<String, Any?>> ?: emptyList()

        val exact = nodes.filter { n ->
            n["id"] == target || (!target.contains(':') && n["key"] == target)
        }
        val (matches, how) = if (exact.isNotEmpty()) exact to null else looseMatch(nodes, target) ?: return null
        val L = ArrayList<String>()
        if (how != null) {
            L.add("_`$target` matched by $how: " + matches.joinToString(", ") { "`${it["id"]}`" } + "_\n")
        } else if (matches.size > 1) {
            L.add("_${matches.size} nodes match `$target`: " +
                matches.joinToString(", ") { "`${it["id"]}`" } + "_\n")
        }
        for (node in matches) {
            renderOne(node, nodes, edges, result, L)
            L.add("")
        }
        return L.joinToString("\n").trimEnd('\n')
    }

    /** The first tier after the exact one that matches anything, with what it matched by; null when none does. */
    private fun looseMatch(nodes: List<Map<String, Any?>>, target: String): Pair<List<Map<String, Any?>>, String>? {
        fun str(n: Map<String, Any?>, k: String) = n[k] as? String
        val bare = !target.contains(':')
        val path = normPath(target)
        val tiers = listOf<Pair<String, (Map<String, Any?>) -> Boolean>>(
            "case" to { n -> str(n, "id").equals(target, true) || (bare && str(n, "key").equals(target, true)) },
            "class name" to { n -> n["type"] == "java" && str(n, "key")?.substringAfterLast('.') == target },
            // The node's file is relative to the project. An agent's tools hand it absolute paths, or paths
            // from the folder above the project, so a target that ends with the node's file names it too.
            "file" to { n ->
                val f = str(n, "file")?.let(::normPath)
                f != null && f.isNotEmpty() && path.isNotEmpty() &&
                    (f == path || f.endsWith("/$path") || f.endsWith("!$path") || path.endsWith("/$f"))
            },
            "label" to { n -> str(n, "label").equals(target, true) },
        )
        for ((how, test) in tiers) {
            var hits = nodes.filter(test)
            // A class's file also backs its endpoints and methods; the class's slice already lists them.
            if (how == "file" && hits.any { it["type"] == "java" }) hits = hits.filter { it["type"] != "endpoint" && it["type"] != "method" }
            if (hits.isNotEmpty()) return hits to how
        }
        return null
    }

    private fun normPath(p: String) = p.replace('\\', '/').removePrefix("./")

    /**
     * The node ids nearest to a [target] nothing matched, best first: those whose id, key, label or file
     * contains it, then those whose key is a few edits away (`ordrProcess`). A typed target is also compared
     * by the part after its type, so `bpmn:orderProcess` still finds `process:orderProcess`.
     */
    @Suppress("UNCHECKED_CAST")
    fun suggest(result: Map<String, Any?>, target: String, limit: Int = 8): List<String> {
        val nodes = (result["graph"] as? Map<String, Any?>)?.get("nodes") as? List<Map<String, Any?>> ?: return emptyList()
        val wanted = listOf(target, target.substringAfter(':', "")).filter { it.isNotEmpty() }.map { it.lowercase() }.distinct()
        if (wanted.isEmpty()) return emptyList()
        val maxDistance = maxOf(2, target.length / 4)
        // Fewer edits than half of either word: two edits turn `zz` into `id`, `a` or `zip`, and a real
        // project has hundreds of short keys — a typo of a short word resembles nothing in particular.
        fun near(w: String, key: String) = distance(w, key).takeIf { it <= maxDistance && 2 * it < minOf(w.length, key.length) }
        val ranked = nodes.mapNotNull { n ->
            val id = n["id"] as? String ?: return@mapNotNull null
            val fields = listOf(id, n["key"], n["label"], n["file"]).mapNotNull { (it as? String)?.lowercase() }
            val key = (n["key"] as? String)?.lowercase().orEmpty()
            when {
                key in wanted -> Triple(id, 0, 0)
                wanted.any { w -> fields.any { it.contains(w) } } -> Triple(id, 1, 0)
                else -> wanted.mapNotNull { near(it, key) }.minOrNull()?.let { Triple(id, 2, it) }
            }
        }
        return ranked.sortedWith(compareBy({ it.second }, { it.third }, { it.first })).map { it.first }.distinct().take(limit)
    }

    /** Levenshtein distance — keys are short, so the plain two-row table is plenty. */
    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
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
                // Marked, not filtered: a slice is what an agent reads before changing this node, and
                // "already accepted" is exactly the context that stops it from re-reporting the finding.
                val waived = (f["waived"] as? Map<*, *>)?.let { w -> " — waived: ${w["reason"]}" } ?: ""
                L.add("- $mark ${f["message"]}$at$waived")
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
