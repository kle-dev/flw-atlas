package com.flowable.atlas.graph

/**
 * The project's health findings — everything Atlas noticed that is probably wrong, as data.
 *
 * These checks existed only as JavaScript inside the HTML explorer (`computeInsights` /
 * `CHECK_CARDS` in `frontend/explorer.js`). Nothing else could see them: not `summary.md`, not
 * `overview.md`, not `CLAUDE.md`, not even `graph.json` — which carried the raw ingredients but never
 * the conclusion. So the summary's one line about script findings pointed the reader at *the
 * explorer's Checks tab*: a Markdown file telling an agent to go look at a GUI.
 *
 * Computing them here instead makes them ordinary result data, available to every renderer, and leaves
 * one definition of each check rather than one per surface.
 *
 * Two result keys are produced:
 *  - **`findings`** — the itemized list, most severe first: `{check, severity, node, label, message}`
 *    plus `file`/`element`/`line`/`snippet` when known. This is what a report can name.
 *  - **`checks`** — `check → count`, plus `open` (the total). This is what a badge or a headline shows.
 *
 * `checks.scriptIssues` counts **findings**, not scripts carrying them, matching `stats.scriptIssues`
 * and the CLI status line. The explorer used to count scripts here, so the same word meant two numbers
 * on two surfaces; it now means one.
 */
object Findings {

    /** Check ids, in the order they are worth reading: broken first, then unfinished, then noise. */
    private val CHECK_ORDER = listOf(
        "parseIssues", "invalidExpr", "scriptIssues", "missingRefs", "changelogIssues",
        "schemaGaps", "suspectExpr", "unusedForms", "unusedOps", "unusedFns", "guessedVars",
    )

    private const val ERROR = "error"
    private const val WARNING = "warning"

    @Suppress("UNCHECKED_CAST")
    fun apply(result: MutableMap<String, Any?>) {
        val graph = result["graph"] as? Map<String, Any?> ?: return
        val nodes = graph["nodes"] as? List<Map<String, Any?>> ?: return
        val edges = graph["edges"] as? List<Map<String, Any?>> ?: emptyList()
        val findings = ArrayList<Map<String, Any?>>()

        // A form is unused when nothing functionally references it. Every form sits in an app, so the
        // app's `contains` edge alone does not count as use.
        val referenced = HashSet<String>()
        for (e in edges) {
            if (e["rel"] == "contains") continue
            (e["t"] as? String)?.let { referenced.add(it) }
        }

        fun add(
            check: String, severity: String, node: Map<String, Any?>, message: String,
            element: String? = null, line: Any? = null, snippet: String? = null,
        ) {
            val f = linkedMapOf<String, Any?>(
                "check" to check,
                "severity" to severity,
                "node" to node["id"],
                "label" to (node["label"] ?: node["key"]),
                "message" to message,
            )
            if (node["file"] != null) f["file"] = node["file"]
            if (element != null) f["element"] = element
            if (line != null) f["line"] = line
            if (snippet != null) f["snippet"] = snippet
            findings.add(f)
        }

        for (n in nodes) {
            val data = n["data"] as? Map<String, Any?> ?: emptyMap()
            when (n["type"]) {
                "expression", "binding" -> {
                    val problems = data["problems"] as? List<Map<String, Any?>> ?: emptyList()
                    if (problems.isEmpty()) continue
                    // One expression can carry several problems; the *expression* is either invalid
                    // (something is definitely wrong) or merely suspect (unknown name), which is the
                    // distinction a reader acts on — so classify it once, by its worst problem.
                    val invalid = problems.any { it["severity"] == ERROR }
                    for (p in problems) {
                        add(
                            check = if (invalid) "invalidExpr" else "suspectExpr",
                            severity = if (p["severity"] == ERROR) ERROR else WARNING,
                            node = n,
                            message = p["message"]?.toString() ?: "expression problem",
                            snippet = p["snippet"]?.toString(),
                        )
                    }
                }
                "form" -> if (n["id"] !in referenced) {
                    add("unusedForms", WARNING, n, "no model or code references this form")
                }
                "liquibase" -> {
                    val authority = data["authority"] as? Map<String, Any?> ?: continue
                    when (authority["status"]) {
                        "orphan" -> add("changelogIssues", WARNING, n,
                            "changelog is referenced by no service or data object")
                        "superseded" -> add("changelogIssues", WARNING, n,
                            "superseded by " + ((authority["supersededBy"] as? List<*>)
                                ?.joinToString(", ") { it.toString() } ?: "another changelog"))
                    }
                }
                "service" -> {
                    val coverage = data["schemaCoverage"] as? Map<String, Any?> ?: continue
                    val rows = coverage["rows"] as? List<Map<String, Any?>> ?: emptyList()
                    for (r in rows) {
                        val what = when (r["status"]) {
                            "no-service" -> "column `${r["sql"]}` of table `${r["table"]}` is in Liquibase " +
                                "but not mapped by the service"
                            "no-dataobject" -> "column `${r["sql"]}` of table `${r["table"]}` is mapped by " +
                                "the service but used by no data object"
                            else -> null
                        } ?: continue
                        add("schemaGaps", WARNING, n, what)
                    }
                }
                "serviceOperation" -> if ((data["usedBy"] as? List<*>).isNullOrEmpty()) {
                    add("unusedOps", WARNING, n, "no model or code calls this operation")
                }
                "customFunction" -> if ((data["usedBy"] as? List<*>).isNullOrEmpty()) {
                    add("unusedFns", WARNING, n, "custom function is registered but never used")
                }
                "external" -> if (data["missingModel"] == true) {
                    add("missingRefs", ERROR, n, "referenced model does not exist in this project")
                }
                "variable" -> if (data["heuristic"] == true) {
                    add("guessedVars", WARNING, n,
                        "only a script mentions this variable — the name is inferred, not declared")
                }
            }
        }

        // Scripts are not graph nodes: their findings hang off the parsed model buckets.
        collectScriptFindings(result, findings)

        // Parse/read failures. Custom-function extraction keeps its own diagnostics list.
        for (d in (result["diagnostics"] as? List<Map<String, Any?>> ?: emptyList())) {
            findings.add(linkedMapOf(
                "check" to "parseIssues",
                "severity" to ERROR,
                "node" to null,
                "label" to (d["path"] ?: "?"),
                "message" to "${d["kind"]}: ${d["message"]}",
                "file" to d["path"],
            ))
        }
        val customFns = result["customFunctions"] as? Map<String, Any?>
        for (d in (customFns?.get("diagnostics") as? List<*> ?: emptyList<Any?>())) {
            findings.add(linkedMapOf(
                "check" to "parseIssues",
                "severity" to ERROR,
                "node" to null,
                "label" to "custom functions",
                "message" to d.toString(),
            ))
        }

        val order = CHECK_ORDER.withIndex().associate { (i, c) -> c to i }
        val sorted = findings.sortedWith(
            compareBy({ order[it["check"]] ?: Int.MAX_VALUE }, { it["label"]?.toString() ?: "" })
        )
        val counts = linkedMapOf<String, Any?>()
        for (c in CHECK_ORDER) {
            val n = sorted.count { it["check"] == c }
            if (n > 0) counts[c] = n
        }
        counts["open"] = sorted.size
        result["findings"] = sorted
        result["checks"] = counts
    }

    /**
     * Script syntax findings, with the element that carries the script.
     *
     * Mirrors the explorer's `allScripts()` sources: BPMN script tasks, scripts anywhere in a CMMN plan
     * tree, execution/task listeners of a model and of its elements, and an action's bot script.
     */
    @Suppress("UNCHECKED_CAST")
    private fun collectScriptFindings(
        result: Map<String, Any?>,
        findings: MutableList<Map<String, Any?>>,
    ) {
        fun emit(modelType: String, model: Map<String, Any?>, element: Any?, problems: Any?) {
            val list = problems as? List<Map<String, Any?>> ?: return
            for (p in list) {
                val f = linkedMapOf<String, Any?>(
                    "check" to "scriptIssues",
                    "severity" to if (p["severity"] == ERROR) ERROR else WARNING,
                    "node" to "$modelType:${model["key"]}",
                    "label" to (model["name"] ?: model["key"]),
                    "message" to (p["message"]?.toString() ?: "script problem"),
                )
                model["file"]?.let { f["file"] = it }
                element?.let { f["element"] = it.toString() }
                p["line"]?.let { f["line"] = it }
                p["snippet"]?.let { f["snippet"] = it }
                findings.add(f)
            }
        }

        for (pAny in (result["processes"] as? List<Map<String, Any?>> ?: emptyList())) {
            for (t in (pAny["scriptTasks"] as? List<Map<String, Any?>> ?: emptyList())) {
                emit("process", pAny, t["id"], t["problems"])
            }
            emitListeners("process", pAny, findings, ::emit)
        }
        for (cAny in (result["cases"] as? List<Map<String, Any?>> ?: emptyList())) {
            // CMMN keeps scripts in the plan tree (`<task flowable:type="script">`).
            fun walk(node: Map<String, Any?>) {
                emit("case", cAny, node["id"], node["problems"])
                for (ch in (node["children"] as? List<Map<String, Any?>> ?: emptyList())) walk(ch)
            }
            (cAny["planModel"] as? Map<String, Any?>)?.let { walk(it) }
            emitListeners("case", cAny, findings, ::emit)
        }
        for (aAny in (result["actions"] as? List<Map<String, Any?>> ?: emptyList())) {
            emit("action", aAny, null, aAny["scriptProblems"])
        }
    }

    /** Listeners of the model itself and of each of its elements — both can hold an inline script. */
    @Suppress("UNCHECKED_CAST")
    private fun emitListeners(
        modelType: String,
        model: Map<String, Any?>,
        findings: MutableList<Map<String, Any?>>,
        emit: (String, Map<String, Any?>, Any?, Any?) -> Unit,
    ) {
        for (l in (model["listeners"] as? List<Map<String, Any?>> ?: emptyList())) {
            emit(modelType, model, null, l["problems"])
        }
        for (bucket in listOf(
            "userTasks", "serviceTasks", "scriptTasks", "ruleTasks", "callActivities",
            "subProcesses", "events", "gateways", "otherTasks",
        )) {
            for (el in (model[bucket] as? List<Map<String, Any?>> ?: emptyList())) {
                for (l in (el["listeners"] as? List<Map<String, Any?>> ?: emptyList())) {
                    emit(modelType, model, el["id"], l["problems"])
                }
            }
        }
    }
}
