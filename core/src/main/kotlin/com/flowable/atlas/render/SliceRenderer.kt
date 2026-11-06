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
 * uses, **who uses it** (the direction the report never renders), which findings touch it, and — for a
 * model — what it contains and which variables it sets and reads.
 *
 * "Who uses it" has two sources, and a slice needs both. Model-to-model references are edges; a variable,
 * an expression, a binding, a string, a custom function or a service operation is never an edge's target,
 * and the models using it are recorded in its own `data.usedBy` (and, for a variable, where and how in
 * `reads`/`writes`). Reading edges alone told an agent that a variable six expressions read was used by
 * nothing.
 */
object SliceRenderer {

    /**
     * Model types whose body the overview renders, and the keys of that body its block shows. Those keys
     * are left out of *Attributes* and of the "more in the graph" pointer: the *Contents* block has them.
     */
    private val FORM_COVERS = setOf("fields", "outcomes", "dataSources", "subforms")
    private val CONTENTS_COVERS: Map<String, Set<String>> = mapOf(
        "process" to setOf(
            "documentation", "candidateStarterGroups", "userTasks", "serviceTasks", "scriptTasks", "ruleTasks",
            "gateways", "otherTasks", "subProcesses", "callActivities", "multiInstance", "events", "listeners",
            "flows", "conditions",
        ),
        "case" to setOf("documentation", "modelRefs", "planModel", "sentries", "eventListeners", "listeners"),
        "decision" to setOf("hitPolicy", "ruleCount", "inputs", "outputs", "rules", "rulesTruncated"),
        "form" to FORM_COVERS,
        "page" to FORM_COVERS,
    )

    /** The heading line already shows these, so *Attributes* repeating them only cost a reader tokens. */
    private val HEADING_KEYS = setOf("key", "name", "file")

    /** Set/read sites listed per variable before eliding; the graph holds them all. */
    private const val SITES_LISTED = 20

    /** Variables listed in a model's *Variables* block — a slice is a page, not a variable dictionary. */
    private const val VARIABLES_LISTED = 40

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
        val byId = nodes.associateBy { it["id"] }
        for (node in matches) {
            renderOne(node, byId, edges, result, L)
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
        byId: Map<Any?, Map<String, Any?>>,
        edges: List<Map<String, Any?>>,
        result: Map<String, Any?>,
        L: MutableList<String>,
    ) {
        val id = node["id"] as? String ?: return
        val type = node["type"]?.toString()
        val data = node["data"] as? Map<String, Any?> ?: emptyMap()
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

        // Outgoing: what this node uses — its edges, and the artifacts the extract attached to it as
        // `_uses` (the expressions a process evaluates, the operations a form calls). Variables get a
        // section of their own below, with the direction of each use.
        val out = edges.filter { it["s"] == id }.groupBy { it["rel"]?.toString() ?: "?" }
        val uses = (data["_uses"] as? Map<String, Any?> ?: emptyMap())
            .mapValues { (_, v) -> (v as? List<*>)?.mapNotNull { it as? String } ?: emptyList() }
            .filter { it.value.isNotEmpty() }
        val usesListed = uses.filterKeys { it != "variable" }
        if (out.isNotEmpty() || usesListed.isNotEmpty()) {
            L.add("## Uses")
            for ((rel, group) in out.entries.sortedBy { it.key }) {
                L.add("- **$rel** → " + Fmt.cap(group.map { describe(it["t"]) }, 20))
            }
            for ((t, ids) in usesListed.entries.sortedBy { it.key }) {
                L.add("- **uses $t** → " + Fmt.cap(ids.map(::describe), 20))
            }
            L.add("")
        }
        // Incoming: who uses this node. This is the half a reader cannot get from the report at all.
        val inc = edges.filter { it["t"] == id }.groupBy { it["rel"]?.toString() ?: "?" }
        val fromEdges = inc.values.flatten().mapNotNull { it["s"] as? String }.toSet()
        val usedIn = (data["usedBy"] as? List<*> ?: emptyList<Any?>()).mapNotNull { it as? String }
            .filter { it !in fromEdges }.distinct()
        if (inc.isNotEmpty() || usedIn.isNotEmpty()) {
            L.add("## Used by")
            for ((rel, group) in inc.entries.sortedBy { it.key }) {
                L.add("- **$rel** ← " + Fmt.cap(group.map { describe(it["s"]) }, 20))
            }
            if (usedIn.isNotEmpty()) L.add("- **used in** ← " + Fmt.cap(usedIn.map(::describe), 20))
            L.add("")
        }
        // A variable's sites: which element sets it and which reads it — what a rename has to change.
        val writes = if (type == "variable") sites(data["writes"]) else emptyList()
        val reads = if (type == "variable") sites(data["reads"]) else emptyList()
        for ((title, list) in listOf("Set by" to writes, "Read by" to reads)) {
            if (list.isEmpty()) continue
            L.add("## $title")
            for (s in list.take(SITES_LISTED)) {
                val el = siteElement(s)
                L.add("- " + describe(s["model"]) + (if (el != null) " — $el" else "") + " (${siteHow(s, code = true)})")
            }
            if (list.size > SITES_LISTED) L.add("- … (+${list.size - SITES_LISTED} more)")
            L.add("")
        }
        if (out.isEmpty() && inc.isEmpty() && uses.isEmpty() && usedIn.isEmpty() && writes.isEmpty() && reads.isEmpty()) {
            L.add("_Nothing references this node, and it references nothing._\n")
        }

        // Findings that name this node — the reason it may be misbehaving — and those about an expression
        // it holds, which sit on the expression's node and would otherwise never reach the model's slice.
        val findings = (result["findings"] as? List<Map<String, Any?>> ?: emptyList())
            .filter { it["node"] == id || (it["models"] as? List<*>)?.contains(id) == true }
        if (findings.isNotEmpty()) {
            L.add("## Findings (${findings.size})")
            for (f in findings.take(20)) {
                val mark = if (f["severity"] == "error") "⚠" else "·"
                val at = f["element"]?.toString()?.let { " at `$it`" }
                    ?: f["label"]?.takeIf { f["node"] != id }?.let { " in `$it`" } ?: ""
                // Marked, not filtered: a slice is what an agent reads before changing this node, and
                // "already accepted" is exactly the context that stops it from re-reporting the finding.
                val waived = (f["waived"] as? Map<*, *>)?.let { w -> " — waived: ${w["reason"]}" } ?: ""
                L.add("- $mark ${f["message"]}$at$waived")
            }
            if (findings.size > 20) L.add("- … (+${findings.size - 20} more)")
            L.add("")
        }

        // A model's own contents, exactly as the overview lists them: what runs in which order, the plan
        // tree, the rule rows, the fields. Neighbours alone left an agent changing one model to fetch the
        // rest from the report or the graph anyway. The overview's `###` heading repeats this page's own.
        val covered = CONTENTS_COVERS[type].orEmpty()
        if (covered.isNotEmpty()) {
            val body = ArrayList<String>()
            when (type) {
                "process" -> OverviewRenderer.renderProcess(data, body)
                "case" -> OverviewRenderer.renderCase(data, body)
                "decision" -> OverviewRenderer.renderDecision(data, body)
                else -> OverviewRenderer.renderForm(data, body, "the graph (`--json`)")
            }
            if (body.firstOrNull()?.startsWith("### ") == true) body.removeAt(0)
            while (body.lastOrNull()?.isBlank() == true) body.removeAt(body.size - 1)
            if (body.isNotEmpty()) {
                L.add("## Contents")
                L.addAll(body)
                L.add("")
            }
        }

        // The variables this node touches, with the direction of each — from the variable nodes' own
        // write and read sites, filtered to this node.
        val vars = uses["variable"].orEmpty()
        if (vars.isNotEmpty()) {
            L.add("## Variables")
            for (vid in vars.take(VARIABLES_LISTED)) {
                val v = byId[vid]
                val vd = v?.get("data") as? Map<String, Any?> ?: emptyMap()
                fun here(k: String) = sites(vd[k]).filter { it["model"] == id }
                    .map { s -> listOfNotNull(siteElement(s), "(${siteHow(s, code = false)})").joinToString(" ") }
                val parts = listOfNotNull(
                    here("writes").takeIf { it.isNotEmpty() }?.let { "set by " + Fmt.cap(it, 6) },
                    here("reads").takeIf { it.isNotEmpty() }?.let { "read by " + Fmt.cap(it, 6) },
                )
                val name = v?.get("key")?.toString() ?: vid.removePrefix("variable:")
                L.add("- `$name`" + (if (parts.isEmpty()) "" else " — " + parts.joinToString(" · ")))
            }
            if (vars.size > VARIABLES_LISTED) L.add("- … (+${vars.size - VARIABLES_LISTED} more)")
            L.add("")
        }

        // The node's own data, minus what is already on the page.
        val shown = covered + HEADING_KEYS + setOf("_uses", "usedBy") +
            (if (type == "variable") setOf("reads", "writes") else emptySet())
        val scalars = data.entries.filter { (k, v) ->
            k !in shown && (v == null || v is String || v is Number || v is Boolean)
        }.filter { Fmt.list(it.value).isNotEmpty() }
        if (scalars.isNotEmpty()) {
            L.add("## Attributes")
            for ((k, v) in scalars) L.add("- **$k**: ${Fmt.list(v)}")
            L.add("")
        }
        val containers = data.keys.filter { k ->
            k !in shown && data[k].let { it is Collection<*> && it.isNotEmpty() || it is Map<*, *> && it.isNotEmpty() }
        }
        if (containers.isNotEmpty()) {
            L.add("_More on this node in the graph: " + containers.sorted().joinToString(", ") { "`$it`" } +
                "._")
        }
    }

    /** A variable's `writes`/`reads`: `{model, via, element?, elementName?, guess?}` each. */
    private fun sites(v: Any?): List<Map<*, *>> = (v as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()

    /** The element a site names, by its name where it has one — what a reader finds in Design. */
    private fun siteElement(s: Map<*, *>): String? =
        (s["elementName"] ?: s["element"])?.toString()?.takeIf { it.isNotBlank() }

    /** How a site sets or reads the variable, and whether Atlas only inferred it from a script. */
    private fun siteHow(s: Map<*, *>, code: Boolean): String {
        val via = s["via"]?.toString() ?: "?"
        return (if (code) "via `$via`" else via) + (if (s["guess"] == true) ", guessed" else "")
    }
}
