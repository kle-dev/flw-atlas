package com.flowable.atlas.graph

import com.flowable.atlas.parsing.Constants

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
 * Three result keys are produced:
 *  - **`findings`** — the itemized list, most severe first: `{check, severity, node, label, message}`
 *    plus `file`/`element`/`subject`/`line`/`snippet` when known, and `waived` on the ones a waiver
 *    covers. This is what a report can name.
 *  - **`checks`** — `check → count` of the **open** ones, plus `open` (the total) and `waived`.
 *    This is what a badge or a headline shows.
 *  - **`waivers`** — present only when a waiver file was read: what it covered, and what went wrong
 *    with it. See [Waivers].
 *
 * `checks.scriptIssues` counts **findings**, not scripts carrying them, matching `stats.scriptIssues`
 * and the CLI status line. The explorer used to count scripts here, so the same word meant two numbers
 * on two surfaces; it now means one.
 */
object Findings {

    private const val ERROR = "error"
    private const val WARNING = "warning"

    @Suppress("UNCHECKED_CAST")
    fun apply(result: MutableMap<String, Any?>, waivers: Waivers.Set = Waivers.EMPTY) {
        val graph = result["graph"] as? Map<String, Any?> ?: return
        val nodes = graph["nodes"] as? List<Map<String, Any?>> ?: return
        val edges = graph["edges"] as? List<Map<String, Any?>> ?: emptyList()
        val findings = ArrayList<Map<String, Any?>>()

        // A form is unused when nothing functionally references it. Every form sits in an app, so the
        // app's `contains` edge alone does not count as use — and neither does an access edge: a group
        // allowed to press a button on a form says who may use it, not that anything opens it.
        val referenced = HashSet<String>()
        for (e in edges) {
            if (e["rel"] == "contains") continue
            if ((e["s"] as? String)?.startsWith("group:") == true) continue
            (e["t"] as? String)?.let { referenced.add(it) }
        }
        // What kind of service a service-registry task calls: only a REST service leaves the engine.
        val serviceTypes = HashMap<String, String>()
        for (n in nodes) {
            if (n["type"] != "service") continue
            val t = (n["data"] as? Map<String, Any?>)?.get("type") as? String ?: continue
            (n["key"] as? String)?.let { serviceTypes[it] = t }
        }

        /**
         * [subject] names *which* of several findings this is, when one check fires more than once on
         * one node — the scope a variable was mapped into, the column a gap is about. Without it the
         * only thing separating those findings is [message], which is generated prose carrying counts
         * and "(+N more)": it rewords when something unrelated on the same node changes, so nothing
         * downstream can name one finding and still mean the same one after the next run.
         */
        fun add(
            check: String, severity: String, node: Map<String, Any?>, message: String,
            element: String? = null, subject: String? = null, line: Any? = null, snippet: String? = null,
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
            if (subject != null) f["subject"] = subject
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
                            // An unknown name identifies itself; a syntax problem has only its position
                            // in the expression, which is stable because the expression text is part of
                            // the node id — edit the expression and this is a different node anyway.
                            subject = p["kind"]?.let { "$it:${p["subject"]}" }
                                ?: p["start"]?.let { "@$it" },
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
                // Like the variable branch below: two independent things can be wrong with one service,
                // so a service without coverage data must still be able to report a crossed mapping.
                "service" -> {
                    literalSecrets(data) { path ->
                        add("hardcodedSecrets", ERROR, n, "`$path` holds a literal value — move it to an expression " +
                            "or an environment setting", subject = path)
                    }
                    for (g in (data["crossedColumns"] as? List<*> ?: emptyList<Any?>())) {
                        val group = g as? Map<String, Any?> ?: continue
                        val pairs = (group["mappings"] as? List<*> ?: emptyList<Any?>())
                            .mapNotNull { it as? Map<String, Any?> }
                        if (pairs.isEmpty()) continue
                        add(
                            check = "crossedColumns",
                            // A closed cycle is a permutation of one name set: nothing but a mistake
                            // pairs them that way. One direction alone can still be a deliberate
                            // mapping onto a legacy column, so it only warns.
                            severity = if (group["kind"] == "crossed") WARNING else ERROR,
                            node = n,
                            message = crossingMessage(group, pairs),
                            subject = pairs.first()["field"]?.toString(),
                        )
                    }
                    val coverage = data["schemaCoverage"] as? Map<String, Any?> ?: continue
                    val rows = coverage["rows"] as? List<Map<String, Any?>> ?: emptyList()
                    // No data object binds this service at all: it is used directly, and "used by no data
                    // object" would be true of every mapped column and say nothing about any one of them.
                    val hasDataObject = !(coverage["dataObjects"] as? List<*>).isNullOrEmpty()
                    for (r in rows) {
                        val what = when (r["status"]) {
                            "no-service" -> "column `${r["sql"]}` of table `${r["table"]}` is in Liquibase " +
                                "but not mapped by the service"
                            "no-dataobject" -> if (!hasDataObject) null else
                                "column `${r["sql"]}` of table `${r["table"]}` is mapped by " +
                                    "the service but used by no data object"
                            else -> null
                        } ?: continue
                        add("schemaGaps", WARNING, n, what, subject = "${r["table"]}.${r["sql"]}")
                    }
                }
                "process" -> {
                    runtimeRiskChecks(data, serviceTypes) { check, message, element -> add(check, WARNING, n, message, element) }
                    topologyChecks(data) { check, message, element -> add(check, WARNING, n, message, element) }
                    secretFields(data) { element, what, field ->
                        add("hardcodedSecrets", ERROR, n, "`$what` sets `$field` to a literal value — move it to " +
                            "an expression or an environment setting", element, subject = field)
                    }
                }
                "case" -> secretFields(data) { element, what, field ->
                    add("hardcodedSecrets", ERROR, n, "`$what` sets `$field` to a literal value — move it to " +
                        "an expression or an environment setting", element, subject = field)
                }
                // A query template that interpolates a value without escaping it lets the value change the
                // query — the injection shape, in the one place a project writes raw search JSON.
                "query" -> for (k in listOf("templateContent", "templateFilter")) {
                    val tpl = data[k] as? String ?: continue
                    for (m in UNESCAPED_TEMPLATE_PARAM.findAll(tpl)) {
                        val inner = m.groupValues[1].trim()
                        val name = inner.takeWhile { it != '.' && it != '[' && it != ' ' && it != '(' }
                        add("unsafeQueries", WARNING, n,
                            "`\${$inner}` in $k is interpolated without escaping — `?json_string` (or `?c` for a " +
                                "number) keeps a value from changing the query", subject = name)
                    }
                }
                // A decision nothing consults is the DMN twin of the unused form: app membership is not use.
                // A decision *service* is the caller, not a table: Design generates one per DRD, and the rule
                // tasks point at the tables it contains.
                "decision" -> if (data["decisionService"] != true && n["id"] !in referenced) {
                    add("unusedDecisions", WARNING, n, "no process, case or decision service calls this decision")
                }
                "channel", "agent", "knowledgeBase" -> literalSecrets(data) { path ->
                    add("hardcodedSecrets", ERROR, n, "`$path` holds a literal value — move it to an expression " +
                        "or an environment setting", subject = path)
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
                // Three independent things can be worth saying about one variable, so this branch must
                // not `continue` between them the way the others do.
                "variable" -> {
                    if (data["heuristic"] == true) {
                        add("guessedVars", WARNING, n,
                            "only a script mentions this variable — the name is inferred, not declared")
                    }
                    if (data["unread"] == true) {
                        add("unusedVars", WARNING, n, "written but never read — " + writeSites(data))
                    }
                    for (scope in (data["unreadIn"] as? List<*> ?: emptyList<Any?>())) {
                        add("unreadInputs", WARNING, n,
                            "mapped into `$scope`, which never reads it — " + writeSites(data),
                            subject = scope?.toString())
                    }
                }
            }
        }

        // Scripts are not graph nodes: their findings hang off the parsed model buckets.
        collectScriptFindings(result, findings)

        // Parse/read failures. Custom-function extraction keeps its own diagnostics list.
        for (d in (result["diagnostics"] as? List<Map<String, Any?>> ?: emptyList())) {
            // A key two model types share is information, not a finding: both files were read completely
            // and everything harvested from them carries its type. Only a bare Java literal is ambiguous,
            // and that edge is already marked suspect.
            if (d["kind"] == "conflict") continue
            findings.add(linkedMapOf(
                "check" to "parseIssues",
                // A file that would not parse is an error: a model is missing from the report. A file
                // Atlas decided not to read was never a model that failed — a warning.
                "severity" to (if (d["kind"] == "skip") WARNING else ERROR),
                "node" to null,
                "label" to (d["path"] ?: "?"),
                "message" to "${d["kind"]}: ${d["message"]}",
                "file" to d["path"],
            ))
        }
        // Leftover markers — a TODO is a promise; the report lists the ones nobody kept. Keyed by the
        // first model the file defines, or by the file when it defines none.
        val nodeById = nodes.associateBy { it["id"] as? String }
        // The same model loose and inside a `.bar` is one model with one marker: the model buckets are
        // deduped that way, and so is this — the first file wins, the finding is one.
        val seenMarkers = HashSet<List<Any?>>()
        for (m in (result["markers"] as? List<Map<String, Any?>> ?: emptyList())) {
            val owner = (m["models"] as? List<*>)?.firstOrNull()?.toString()?.let { nodeById[it] }
            val text = m["text"]?.toString().orEmpty()
            // A marker with no text of its own is told apart by where it sits: the path of the JSON element
            // holding it (a minified model is one line, and a column moves on every export), else its line.
            val subject = text.ifEmpty { m["path"]?.toString() ?: "@${m["line"]}" }
            if (!seenMarkers.add(listOf(owner?.get("id") ?: m["file"], m["marker"], subject, m["line"]))) continue
            findings.add(linkedMapOf(
                "check" to "leftoverMarkers",
                "severity" to WARNING,
                "node" to owner?.get("id"),
                "label" to (owner?.get("label") ?: owner?.get("key") ?: m["file"]),
                "message" to "${m["marker"]}" + (if (text.isEmpty()) " left in the model" else ": $text"),
                "file" to m["file"],
                "line" to m["line"],
                "subject" to subject,
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

        val order = CheckCatalog.ORDER.withIndex().associate { (i, c) -> c to i }
        val sorted = findings.sortedWith(
            compareBy({ order[it["check"]] ?: Int.MAX_VALUE }, { it["label"]?.toString() ?: "" })
        )

        // A waived finding is kept, marked, and left out of the counts. It is not dropped: "nothing is
        // silent" is the promise the whole report is built on, and a suppression that also hides the
        // thing it suppressed leaves a reader unable to see what a team decided to live with.
        val matching = Waivers.Matching(waivers)
        val marked = sorted.map { f ->
            val w = matching.match(f) ?: return@map f
            LinkedHashMap(f).apply {
                put("waived", linkedMapOf<String, Any?>("reason" to w.reason).also { m ->
                    w.by?.let { m["by"] = it }
                    w.at?.let { m["at"] = it }
                    w.until?.let { m["until"] = it }
                })
            }
        }
        val open = marked.filter { it["waived"] == null }

        val counts = linkedMapOf<String, Any?>()
        for (c in CheckCatalog.ORDER) {
            val n = open.count { it["check"] == c }
            if (n > 0) counts[c] = n
        }
        counts["open"] = open.size
        val waivedCount = marked.size - open.size
        if (waivedCount > 0) counts["waived"] = waivedCount
        result["findings"] = marked
        result["checks"] = counts
        // The two numbers every headline leads with. In `stats`, not `checks`: every consumer of `checks`
        // treats each key but `open`/`waived` as a check id, and two more pseudo-ids would break them.
        (result["stats"] as? MutableMap<String, Any?>)?.let { st ->
            st["defects"] = CheckCatalog.countOpen(open, CheckCatalog.DEFECT)
            st["advice"] = CheckCatalog.countOpen(open, CheckCatalog.ADVICE)
        }
        waiverReport(matching)?.let { result["waivers"] = it }
    }

    /**
     * What the waiver file did, for every surface that has to say so. Absent when there is no file: an
     * empty block in every report would read as a feature nobody is using rather than as one nobody
     * needed here.
     */
    private fun waiverReport(matching: Waivers.Matching): Map<String, Any?>? {
        val waivers = matching.set
        if (waivers.isEmpty) return null
        // The rules travel whole — author, date and expiry included — because the explorer edits this
        // block and writes it back as waivers.json; a field it never received is a field it would drop.
        val rules = waivers.waivers.mapIndexed { i, w ->
            linkedMapOf<String, Any?>("check" to w.check, "node" to w.node).also { m ->
                w.element?.let { m["element"] = it }
                w.subject?.let { m["subject"] = it }
                m["reason"] = w.reason
                w.by?.let { m["by"] = it }
                w.at?.let { m["at"] = it }
                w.until?.let { m["until"] = it }
                m["matched"] = matching.matchCount(i)
            }
        }
        val out = linkedMapOf<String, Any?>("rules" to rules)
        waivers.createdWith?.let { out["createdWith"] = it }
        if (waivers.notes.isNotEmpty()) {
            out["notes"] = waivers.notes.map { n ->
                linkedMapOf<String, Any?>("node" to n.node).also { m ->
                    n.check?.let { m["check"] = it }
                    n.element?.let { m["element"] = it }
                    n.subject?.let { m["subject"] = it }
                    m["text"] = n.text
                    m["importance"] = n.importance
                    n.by?.let { m["by"] = it }
                    n.at?.let { m["at"] = it }
                }
            }
        }
        // Three ways a waiver file goes wrong, each reported rather than fixed silently: it points at
        // something that is gone, it ran out, or it never said why — and the last one is the only thing
        // a reviewer could actually have reviewed.
        val stale = matching.stale()
        if (stale.isNotEmpty()) out["stale"] = stale
        val unexplained = waivers.unexplained()
        if (unexplained.isNotEmpty()) out["unexplained"] = unexplained
        if (waivers.problems.isNotEmpty()) out["problems"] = waivers.problems
        return out
    }

    /** Every BPMN element list that carries the shared engine-behaviour extras. */
    private val ELEMENT_LISTS = listOf(
        "userTasks", "serviceTasks", "scriptTasks", "ruleTasks", "callActivities", "subProcesses",
        "events", "gateways", "otherTasks",
    )

    /**
     * How a process is configured to behave at runtime, as opposed to whether its references resolve.
     *
     * Each of these is a question rather than a verdict — "is that deliberate?" — which is why they warn
     * and why each one stays quiet wherever the model does not give enough to be sure.
     */
    @Suppress("UNCHECKED_CAST")
    private fun runtimeRiskChecks(
        data: Map<String, Any?>, serviceTypes: Map<String, String>, report: (String, String, String) -> Unit,
    ) {
        val events = data["events"] as? List<Map<String, Any?>> ?: emptyList()
        // Errors caught anywhere in the process: a boundary event on a task, and an error event
        // subprocess, which catches for the whole process.
        val guarded = HashSet<String>()
        for (e in events) {
            if (e["def"] == "error") (e["attachedTo"] as? String)?.let { guarded.add(it) }
        }
        // An error *start* event catches — in an event subprocess it catches for the whole process. An
        // error *end* event throws, and counting it as a catch would silence the check on exactly the
        // processes that raise errors.
        val catchesEverywhere = events.any { it["def"] == "error" && it["type"] == "startEvent" }
        // A task inside a subprocess is guarded by a boundary event on that subprocess, and the element
        // lists are flat, so containment cannot be recovered here. Rather than report a task that is
        // already handled one level up, the check stands down for the whole process when a subprocess
        // carries an error boundary.
        val subprocessGuarded = (data["subProcesses"] as? List<Map<String, Any?>> ?: emptyList())
            .any { guarded.contains(it["id"]) }

        for (list in ELEMENT_LISTS) {
            for (el in (data[list] as? List<Map<String, Any?>> ?: emptyList())) {
                val id = el["id"] as? String ?: continue
                val what = (el["name"] as? String)?.ifEmpty { null } ?: id
                if (el["async"] != null) {
                    // `exclusive` defaults to true in the engine and is only ever written to say false,
                    // so its presence here is an explicit opt-out: jobs of this process instance may then
                    // run at the same time, which is an optimistic-locking risk unless it was intended.
                    if (el["exclusive"] == "false") {
                        report("nonExclusiveAsync",
                            "`$what` is async with exclusive=false — its jobs can run concurrently with " +
                                "other jobs of the same process instance", id)
                    }
                    if (el["retryTimeCycle"] == null) {
                        report("asyncWithoutRetry",
                            "`$what` is async with no failedJobRetryTimeCycle — the engine default applies",
                            id)
                    }
                }
            }
        }

        if (catchesEverywhere || subprocessGuarded) return
        for (el in (data["serviceTasks"] as? List<Map<String, Any?>> ?: emptyList())) {
            val id = el["id"] as? String ?: continue
            // An async task's failure is a failed job — retried, then an incident for an administrator —
            // and never an exception to whoever completed the previous step. That *is* its error path;
            // `asyncWithoutRetry` judges the rest. (83 async mail tasks on one project said otherwise.)
            if (el["async"] != null) continue
            if (!leavesTheEngine(el, serviceTypes) || guarded.contains(id)) continue
            // An HTTP task told to swallow failures, or to map status codes itself, has its error path.
            val fields = el["fields"] as? Map<*, *>
            if (fields != null && (fields.containsKey("ignoreException") || fields.containsKey("handleStatusCodes"))) continue
            val what = (el["name"] as? String)?.ifEmpty { null } ?: id
            report("unguardedTasks",
                "`$what` calls out of the engine with no error boundary event — a failure propagates to " +
                    "the caller", id)
        }
    }

    /** Task types whose work happens outside the engine, whatever bean Design wrote for them. */
    private val LEAVING_TASK_TYPES = setOf("http", "external-worker", "agent", "mail")

    /** The root name of `${bean}` / `${bean.method(x)}` / `#{bean}`. */
    private val EXPR_ROOT_RE = Regex("^\\s*[#$]\\{\\s*([A-Za-z_]\\w*)")

    /** `${bpmn:removeAssignee()}` — a platform function namespace, not a bean: the call stays in the engine. */
    private val EXPR_NAMESPACE_RE = Regex("^\\s*[#$]\\{\\s*[A-Za-z_]\\w*\\s*:")

    /**
     * Whether a service task's work happens outside the engine — the one thing that makes a missing
     * error path a risk. Design writes a platform bean into every task type's `delegateExpression`, so
     * a delegate alone says nothing: `${initVariablesService}` sets variables and `${auditLogService}`
     * writes a row, and neither depends on anyone else's uptime. Before this distinction the check fired
     * on 94 % platform beans across real projects. What leaves: the task types that call out (HTTP,
     * external worker, agent, mail), code of the project's own (`class`, a bean that is not the
     * platform's, an `expression` whose root is neither an engine context nor a platform bean), and a
     * service-registry task whose service is REST.
     */
    private fun leavesTheEngine(el: Map<String, Any?>, serviceTypes: Map<String, String>): Boolean {
        if (el["type"] in LEAVING_TASK_TYPES) return true
        if (!(el["class"] as? String).isNullOrEmpty()) return true
        fun root(attr: String) = (el[attr] as? String)?.let { EXPR_ROOT_RE.find(it)?.groupValues?.get(1) }
        root("expression")?.let { r ->
            val namespaced = (el["expression"] as? String)?.let { EXPR_NAMESPACE_RE.containsMatchIn(it) } == true
            if (!namespaced && r !in Constants.FLOWABLE_CONTEXT && r !in Constants.FLOWABLE_PLATFORM_BEANS) return true
        }
        val bean = root("delegateExpression") ?: return false
        if (bean == "serviceRegistryService" || el["type"] == "service-registry") {
            val svc = el["serviceModelKey"] as? String ?: return false
            return serviceTypes[svc].equals("REST", ignoreCase = true)
        }
        return bean !in Constants.FLOWABLE_PLATFORM_BEANS
    }

    /** `${name}` in a query template with no FreeMarker built-in behind the name — nothing escapes it. */
    private val UNESCAPED_TEMPLATE_PARAM = Regex("\\$\\{([^}?]*)\\}")

    /** Every path the parser recorded a literal secret under, for a JSON model. */
    @Suppress("UNCHECKED_CAST")
    private fun literalSecrets(data: Map<String, Any?>, report: (String) -> Unit) {
        for (p in (data["literalSecrets"] as? List<*> ?: emptyList<Any?>())) report(p.toString())
    }

    /**
     * Every element of a process or case whose field injections carry a literal secret. A process keeps
     * its elements in flat lists; a case keeps a plan tree, walked through `children`.
     */
    @Suppress("UNCHECKED_CAST")
    private fun secretFields(data: Map<String, Any?>, report: (String, String, String) -> Unit) {
        fun visit(el: Map<String, Any?>) {
            val id = el["id"] as? String ?: return
            val what = (el["name"] as? String)?.ifEmpty { null } ?: id
            for (f in (el["secretFields"] as? List<*> ?: emptyList<Any?>())) report(id, what, f.toString())
        }
        for (list in ELEMENT_LISTS) for (el in (data[list] as? List<Map<String, Any?>> ?: emptyList())) visit(el)
        fun walk(node: Map<String, Any?>) {
            visit(node)
            for (c in (node["children"] as? List<Map<String, Any?>> ?: emptyList())) walk(c)
        }
        (data["planModel"] as? Map<String, Any?>)?.let { walk(it) }
    }

    /**
     * How the sequence flows are wired, as opposed to how the elements are configured. Both questions
     * here have a definite consequence at runtime — an exception, or a fork nobody drew — and both stay
     * quiet wherever the model gives the engine a way out.
     */
    @Suppress("UNCHECKED_CAST")
    private fun topologyChecks(data: Map<String, Any?>, report: (String, String, String) -> Unit) {
        val flows = data["flows"] as? List<Map<String, Any?>> ?: return
        val outgoing = HashMap<String, MutableList<Map<String, Any?>>>()
        for (f in flows) (f["from"] as? String)?.let { outgoing.getOrPut(it) { ArrayList() }.add(f) }

        for (g in (data["gateways"] as? List<Map<String, Any?>> ?: emptyList())) {
            val id = g["id"] as? String ?: continue
            // Only the gateways that choose: a parallel gateway takes every flow, an event gateway waits.
            if (g["type"] != "exclusiveGateway" && g["type"] != "inclusiveGateway") continue
            val outs = outgoing[id] ?: continue
            if (g["default"] != null) continue
            // An unconditional flow is the way out when nothing else matches; with one there is no risk. A
            // single conditional flow is no choice, but it throws the very same exception when it is false.
            if (outs.any { it["condition"] == null }) continue
            val what = (g["name"] as? String)?.ifEmpty { null } ?: id
            val n = outs.size
            report("gatewayNoDefault",
                "`$what` has $n conditional outgoing flow${if (n == 1) "" else "s"} and no default — when " +
                    "${if (n == 1) "it is" else "none of them is"} true the engine throws \"no outgoing sequence flow\"", id)
        }

        // A DMN service task sits in `serviceTasks` and in `ruleTasks`: one element, judged once.
        val seen = HashSet<String>()
        for (list in ELEMENT_LISTS) {
            if (list == "gateways") continue
            for (el in (data[list] as? List<Map<String, Any?>> ?: emptyList())) {
                val id = el["id"] as? String ?: continue
                if (!seen.add(id)) continue
                val outs = outgoing[id] ?: continue
                if (outs.size < 2) continue
                // The engine takes every unconditional flow, so two of them are a fork with no gateway
                // saying so. A set of flows that are all conditional is a choice someone drew on purpose,
                // and stays quiet — and so does an activity's own default flow, which BPMN allows and the
                // engine takes only when no condition holds.
                val plain = outs.count { it["condition"] == null && it["default"] != true }
                if (plain == 0) continue
                val what = (el["name"] as? String)?.ifEmpty { null } ?: id
                val how = if (plain == outs.size) "all of them run in parallel"
                    else "the unconditional one${if (plain > 1) "s" else ""} run${if (plain > 1) "" else "s"} in parallel with whichever condition holds"
                report("implicitSplit", "`$what` has ${outs.size} outgoing flows and no gateway — $how", id)
            }
        }
    }

    /**
     * One [CrossedColumns] group in words. The message spells out both halves of the pairing, because
     * the reader has to compare two names to see the defect at all — "the mapping looks crossed" alone
     * would send them back to the model to find out which one.
     */
    private fun crossingMessage(group: Map<String, Any?>, pairs: List<Map<String, Any?>>): String {
        fun arrow(p: Map<String, Any?>) = "`${p["field"]}` → `${p["column"]}`"
        val first = pairs.first()
        val cycle = group["kind"] != "crossed"
        // Keyed off how many mappings are actually there rather than off the kind alone: the wording
        // then cannot read the second half of a pair the group does not carry.
        return when {
            cycle && pairs.size == 2 -> "`${first["field"]}` maps to column `${first["column"]}` and " +
                "`${pairs[1]["field"]}` maps to `${pairs[1]["column"]}` — the two column mappings " +
                "look swapped"
            cycle -> "${pairs.size} column mappings form a rotation: " + pairs.joinToString(", ") { arrow(it) }
            else -> "`${first["field"]}` maps to column `${first["column"]}`, but the table's own " +
                "`${group["expected"]}` is the column its name points at" +
                (group["otherField"]?.let { " — `$it` maps that one" } ?: ", and no field maps it")
        }
    }

    /**
     * Where a variable is written, in Design's words — `written by Script task "Stamp order"`. A finding
     * that only says "never read" leaves the reader hunting for the line to delete.
     */
    @Suppress("UNCHECKED_CAST")
    private fun writeSites(data: Map<String, Any?>): String {
        val writes = data["writes"] as? List<Map<String, Any?>> ?: return "written somewhere"
        val named = writes.take(3).map { w ->
            val what = VIA_TERMS[w["via"]] ?: w["via"]?.toString() ?: "a mapping"
            val where = (w["elementName"] as? String)?.ifEmpty { null } ?: w["element"] as? String
            if (where != null) "$what `$where`" else what
        }
        val total = (data["writeCount"] as? Number)?.toInt() ?: writes.size
        return "written by " + named.joinToString(", ") + (if (total > named.size) " (+${total - named.size} more)" else "")
    }

    /** Design's wording for each way a variable can be written. */
    private val VIA_TERMS = mapOf(
        "inParameter" to "an in parameter on",
        "outParameter" to "an out parameter on",
        "resultVariable" to "the result variable of",
        "outputVariableName" to "the output variable of",
        "responsePayloadMapping" to "a stored response attribute of",
        "errorResponsePayloadMapping" to "a stored error attribute of",
        "eventOutParameter" to "an event out parameter on",
        "variableMapping" to "an init-variables mapping on",
        "scriptApi" to "a script on",
        "dmnOutput" to "a decision output of",
        "formField" to "a form field",
        "formOutcome" to "the outcome variable of",
        "formProperty" to "a form property",
        "dataObject" to "a data-object declaration in",
        "multiInstanceElement" to "a multi-instance element variable",
        "initiator" to "the initiator variable",
        "variableExtractor" to "a variable extractor",
        "flwPayload" to "an action payload output of",
        "javaApi" to "Java code",
    )

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
        // No `subject` here, deliberately. One script element can carry several problems, but the only
        // things that would tell them apart are the line and the message, and both move when the script
        // is edited above them. The element is the honest grain: a reader judges a script body, not a
        // line of it.
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
