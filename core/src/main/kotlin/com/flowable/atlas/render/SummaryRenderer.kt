package com.flowable.atlas.render

import com.flowable.atlas.model.DesignTerms
import java.io.File

/**
 * A compact (~few KB) LLM-first overview: the essentials + how to dig deeper.
 *
 * Byte-exact port of `flowable_atlas.py` `summary_render(result, root)` (+ its inner `cap` helper).
 * Builds a `List<String>` and joins it with `"\n"`, mirroring Python's `L.append` / `"\n".join(L)`.
 * `result` values are the [LinkedHashMap]/[ArrayList]/[Double]/[String]/[Boolean]/null tree produced
 * by [com.flowable.atlas.graph.Atlas.extract]; integer counts print without a trailing `.0`.
 */
object SummaryRenderer {

    /**
     * Human wording for the [com.flowable.atlas.graph.Findings] check ids. Shared with the overview so
     * one check is called the same thing wherever it appears.
     */
    internal val CHECK_LABELS = linkedMapOf(
        "parseIssues" to "unparseable files",
        "invalidExpr" to "invalid expressions",
        "scriptIssues" to "script syntax",
        "missingRefs" to "missing models",
        "changelogIssues" to "changelog problems",
        "schemaGaps" to "schema gaps",
        "suspectExpr" to "suspect expressions",
        "unusedForms" to "unused forms",
        "unusedOps" to "unused service operations",
        "unusedFns" to "unused custom functions",
        "unusedVars" to "variables never read",
        "unreadInputs" to "unread call parameters",
        "guessedVars" to "script-inferred variables",
    )

    /** Well-known Flowable platform service-task beans (engine-provided) — mirrors the Python set. */
    private val FLOWABLE_PLATFORM_BEANS = setOf(
        "initVariablesService", "dataObjectServiceTask", "generateDocumentService",
        "createDocumentService", "serviceRegistryService", "agentService",
        "sendEventServiceTask", "auditLogService", "decisionServiceTask",
        "caseServiceTask", "httpServiceTask", "scriptServiceTask", "mailServiceTask",
    )

    @Suppress("UNCHECKED_CAST")
    fun render(result: Map<String, Any?>, root: File): String {
        val g = result["graph"] as Map<String, Any?>
        val nodes = g["nodes"] as List<Map<String, Any?>>
        val edges = g["edges"] as List<Map<String, Any?>>
        val st = result["stats"] as Map<String, Any?>

        val byId = LinkedHashMap<String, Map<String, Any?>>()
        for (n in nodes) byId[n["id"] as String] = n
        val byType = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        for (n in nodes) byType.getOrPut(n["type"] as String) { ArrayList() }.add(n)
        fun bt(t: String): List<Map<String, Any?>> = byType[t] ?: emptyList()

        val jroles = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        for (n in bt("java")) {
            for (r in ((n["data"] as Map<String, Any?>)["roles"] as? List<String> ?: emptyList())) {
                jroles.getOrPut(r) { ArrayList() }.add(n)
            }
        }

        fun cap(items: List<String>, n: Int = 15): String = Fmt.cap(items, n)

        // Sibling artifact names, so every pointer below names a file the reader can open rather than a
        // CLI flag it cannot run or an explorer tab it cannot see.
        val an = Fmt.artifactName(root)

        val L = ArrayList<String>()
        L.add("# Flowable project — `${root.absoluteFile.name}` (quick overview)\n")
        L.add("_${st["models"]} model files · ${st["java"]} Java files · ${st["nodes"] ?: 0} nodes · " +
                "${st["edges"] ?: 0} relationships · ${st["groups"] ?: 0} user groups. " +
                "Compact summary — full report in `$an.overview.md`, full graph in `$an.graph.json`._\n")
        val diags = result["diagnostics"] as? List<*> ?: emptyList<Any?>()
        if (diags.isNotEmpty()) {
            L.add("⚠ **${diags.size} file(s) could not be fully analyzed** (parse/read failures) — " +
                    "the map below may be incomplete. Details: the Findings section of " +
                    "`$an.overview.md`, or `diagnostics` in `$an.graph.json`.\n")
        }

        // Apps
        val appModels = LinkedHashMap<String, Int>()
        for (e in edges) {
            if (e["rel"] == "contains" && (e["s"] as String).startsWith("app:")) {
                val s = e["s"] as String
                appModels[s] = (appModels[s] ?: 0) + 1
            }
        }
        if (bt("app").isNotEmpty()) {
            L.add("## Apps")
            for (a in bt("app").sortedByDescending { appModels[it["id"] as String] ?: 0 }) {
                L.add("- **${a["label"]}** (`${a["key"]}`) — ${appModels[a["id"] as String] ?: 0} models")
            }
            L.add("")
        }

        // Inventory
        val order = listOf("process", "case", "decision", "form", "page", "dataObject", "dataDictionary",
            "service", "agent", "channel", "event", "action", "query", "template", "sequence",
            "securityPolicy", "variableExtractor", "liquibase")
        // Design's word for each type, counted — "2 data objects", not "2 dataObject".
        val inv = order.filter { bt(it).isNotEmpty() }.map { DesignTerms.counted(bt(it).size, it) }
        L.add("## Inventory")
        L.add("Models: " + inv.joinToString(" · "))
        if (jroles.isNotEmpty()) {
            L.add("Java: " + jroles.entries.sortedByDescending { it.value.size }
                .joinToString(" · ") { "${it.value.size} ${it.key}" })
        }
        val ne = bt("expression").size
        val nb = bt("binding").size
        val nv = bt("variable").size
        val ns = bt("string").size
        if (nv != 0) {
            // The scopes a variable actually appears in, counted — the line used to print the words
            // "process / form / case / java / …" as if they were data. `heuristic` variables are the
            // ones only a script mentions, so a reader knows which names are inferred, not declared.
            val scopeCount = LinkedHashMap<String, Int>()
            var guessed = 0
            for (n in bt("variable")) {
                val d = n["data"] as? Map<String, Any?> ?: continue
                if (d["heuristic"] == true) guessed++
                for (sc in (d["scopes"] as? List<*> ?: emptyList<Any?>())) {
                    val s = sc?.toString() ?: continue
                    scopeCount[s] = (scopeCount[s] ?: 0) + 1
                }
            }
            // `name count`, not `count name`: a variable can live in several scopes, so these do not
            // partition the total and must not read as if they did.
            val scopes = scopeCount.entries.sortedByDescending { it.value }
                .joinToString(" · ") { "${it.key} ${it.value}" }
            L.add("Variables: $nv" + (if (scopes.isNotEmpty()) " — scopes: $scopes" else "") +
                    (if (guessed != 0) " · $guessed inferred from scripts" else ""))
        }
        if (ne != 0 || nb != 0 || ns != 0) {
            L.add("Expressions: $ne backend \${ } · $nb frontend {{ }} · $ns string literals")
        }
        L.add("")

        // Entry points
        val access = result["access"] as? List<Map<String, Any?>> ?: emptyList()
        val starts = access.filter { it["action"] == "start" }
        if (starts.isNotEmpty()) {
            L.add("## Entry points — who can start what")
            // Grouped by audience. One line per model spent ~1.2 KB on 17 near-identical
            // "← flowableUser" lines in a real project: the same single bit of information, 17 times.
            val byAudience = LinkedHashMap<String, MutableList<String>>()
            for (a in starts.sortedBy { it["model"] as String }) {
                val groups = a["groups"] as? List<String> ?: emptyList()
                val users = a["users"] as? List<String> ?: emptyList()
                val joined = (groups + users).joinToString(", ")
                val who = if (joined.isEmpty()) "(no restriction)" else joined
                byAudience.getOrPut(who) { ArrayList() }.add("${a["modelType"]} `${a["model"]}`")
            }
            for ((who, models) in byAudience.entries.sortedByDescending { it.value.size }) {
                L.add("- **$who** ← ${cap(models, 12)}")
            }
            L.add("")
        }
        if (bt("endpoint").isNotEmpty()) {
            val ctrls = bt("endpoint").mapNotNull { n ->
                val c = (n["data"] as Map<String, Any?>)["controller"]
                if (truthy(c)) c as String else null
            }.distinct().sorted()
            L.add("## REST API surface\n${bt("endpoint").size} endpoints across ${ctrls.size} controllers: " +
                    cap(ctrls) + "\n")
        }

        // Integrations
        if (bt("service").isNotEmpty()) {
            L.add("## Integrations — services")
            for (s in bt("service").take(15)) {
                val d = s["data"] as Map<String, Any?>
                val loc: Any? = when {
                    truthy(d["baseUrl"]) -> d["baseUrl"]
                    truthy(d["tableName"]) -> d["tableName"]
                    else -> "?"
                }
                L.add("- `${s["key"]}` ${s["label"]} (${pystr(d["type"])} → $loc)")
            }
            if (bt("service").size > 15) L.add("- … (+${bt("service").size - 15} more)")
            L.add("")
        }
        val misc = ArrayList<String>()
        if (bt("channel").isNotEmpty()) misc.add("Channels: " + cap(bt("channel").map { it["label"] as String }))
        if (bt("event").isNotEmpty()) misc.add("Events: " + cap(bt("event").map { it["key"] as String }))
        if (bt("agent").isNotEmpty()) misc.add("Agents: " + cap(bt("agent").map { it["key"] as String }))
        if (misc.isNotEmpty()) L.add("## Integrations — messaging / AI\n" + misc.joinToString("  \n") + "\n")

        // Java glue (model <-> code)
        val glue = ArrayList<String>()
        for ((role, label) in listOf("delegate" to "Delegates", "bot" to "Bots", "listener" to "Listeners")) {
            val jr = jroles[role]
            if (jr != null && jr.isNotEmpty()) {
                glue.add("**$label (${jr.size}):** " + cap(jr.map { it["label"] as String }))
            }
        }
        if (glue.isNotEmpty()) L.add("## Java glue (wired to models)\n" + glue.joinToString("  \n") + "\n")

        // Hotspots — most-referenced nodes (excluding the uniform app 'contains')
        val indeg = LinkedHashMap<String, Int>()
        for (e in edges) {
            if (e["rel"] == "contains") continue
            val t = e["t"] as String
            indeg[t] = (indeg[t] ?: 0) + 1
        }
        // A `method:` node duplicates the `java:` node that owns it (`Svc` at 19, `Svc#doIt` at 18), and
        // three of twelve hotspot slots went to such pairs in a real project. Keep the class, drop the
        // method — the class is the thing a reader navigates to.
        val hotJavaClasses = indeg.keys.filter { it.startsWith("java:") }
            .map { it.removePrefix("java:") }.toSet()
        val hot = indeg.entries
            .filter { val n = byId[it.key]; n != null && n["type"] != "group" }
            .filterNot { e ->
                e.key.startsWith("method:") &&
                    hotJavaClasses.any { e.key.removePrefix("method:").startsWith("$it#") }
            }
            .map { it.value to it.key }
            .sortedWith(compareByDescending<Pair<Int, String>> { it.first }.thenByDescending { it.second })
            .take(12)
        if (hot.isNotEmpty()) {
            L.add("## Hotspots — most-referenced (central) artifacts")
            for ((c, nid) in hot) {
                val n = byId[nid]!!
                val labelPart = if (n["label"] != n["key"]) "— ${n["label"]}" else ""
                L.add("- ${n["type"]} `${n["key"]}` $labelPart (referenced by $c)")
            }
            L.add("")
        }

        // External / unresolved surface
        val unresolved = result["unresolvedRefs"] as? List<Map<String, Any?>> ?: emptyList()
        val groupPairs = LinkedHashSet<Pair<String, String>>()
        for (r in unresolved) groupPairs.add((r["kind"] as String) to (r["value"] as String))
        val platform = groupPairs.filter { it.first == "bean" && it.second in FLOWABLE_PLATFORM_BEANS }
            .map { it.second }.distinct().sorted()
        val review = groupPairs.filter { !(it.first == "bean" && it.second in FLOWABLE_PLATFORM_BEANS) }
            .map { "${it.first}:${it.second}" }.distinct().sorted()
        val extUrls = bt("external").count { truthy((it["data"] as Map<String, Any?>)["external_url"]) }
        L.add("## External surface")
        if (platform.isNotEmpty()) L.add("- Flowable platform beans (engine-provided): " + cap(platform))
        if (extUrls != 0) L.add("- External REST URLs called: $extUrls")
        L.add("- Review (unresolved in project — likely missing/external): " +
                (if (review.isNotEmpty()) cap(review) else "none ✅"))
        val suspectN = (result["resolvedRefs"] as? List<Map<String, Any?>>)?.count { it["suspect"] == true } ?: 0
        val dynN = (result["dynamicRefs"] as? List<*>)?.size ?: 0
        if (suspectN + dynN != 0) {
            L.add("- Uncertain links: $suspectN suspect (loose/cross-type match) · $dynN dynamic (expression-valued)")
        }
        L.add("")

        // What is probably wrong here. These used to be computed only inside the HTML explorer, so the
        // summary could only say "see the explorer's Checks tab" — useless to an agent holding the file.
        val checks = result["checks"] as? Map<String, Any?> ?: emptyMap()
        val open = (checks["open"] as? Number)?.toInt() ?: 0
        if (open > 0) {
            val findings = result["findings"] as? List<Map<String, Any?>> ?: emptyList()
            L.add("## Health — $open open finding(s)")
            L.add(checks.entries.filter { it.key != "open" }
                .joinToString(" · ") { "${CHECK_LABELS[it.key] ?: it.key}: ${it.value}" })
            // Name the worst few; the overview lists them all with file/line.
            for (f in findings.filter { it["severity"] == "error" }.take(5)) {
                val where = listOfNotNull(f["label"]?.toString(), f["element"]?.toString())
                    .joinToString(" · ")
                L.add("- ⚠ ${f["message"]}" + (if (where.isNotEmpty()) " — `$where`" else ""))
            }
            val errors = findings.count { it["severity"] == "error" }
            if (errors > 5) L.add("- … (+${errors - 5} more errors — see `$an.overview.md`)")
            L.add("")
        }
        // Expressions that got no verdict are not "fine": say how many, so the health block above is
        // read as "of what was judged".
        val notJudged = ((result["stats"] as? Map<*, *>)?.get("exprSkippedNested") as? Number)?.toInt() ?: 0
        if (notJudged > 0) {
            L.add("_${notJudged} expression(s) not validated — the harvested text holds a nested `{` and may be truncated._")
            L.add("")
        }

        L.add("---\n_Next: `$an.overview.md` has every model, relationship and the access map · " +
                "`$an.graph.json` is the traversable graph to query · `$an.explorer.html` is the clickable view · " +
                "regenerate with `atlas <project-dir>`._")
        return L.joinToString("\n")
    }

    /** Python truthiness for `.get(...) or …` / `if x:` idioms (None/""/0/empty → false). */
    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is String -> v.isNotEmpty()
        is Number -> v.toDouble() != 0.0
        is Collection<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        else -> true
    }

    /** Python `str(x)` / f-string interpolation of a possibly-`None` value (`None` → "None"). */
    private fun pystr(v: Any?): String = v?.toString() ?: "None"
}
