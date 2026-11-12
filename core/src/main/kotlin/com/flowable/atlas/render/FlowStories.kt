package com.flowable.atlas.render

import com.flowable.atlas.model.Dyn
import com.flowable.atlas.model.MiniJson

/**
 * The storylines: each process and each case as the tree a reader would tell it in — what happens
 * first, where it branches and under which condition, where the branches meet again, what a called
 * process does inside.
 *
 * The report's process section lists every element in execution order with a `next:` on each line
 * (see [OverviewRenderer.flowOrder]). That is complete, and it is a list: which steps belong to the
 * "rejected" path and where that path rejoins is for the reader to work out from the ids. A storyline
 * is the same flows, nested — a gateway holds its branches, a branch holds only what is its own, and
 * what follows the join follows the gateway.
 *
 * Rendered twice from this one structure, so the two cannot tell different stories: the explorer's
 * Storylines page reads it from its data island, and `--flow` writes it as `<name>.flow.json`.
 *
 * What it deliberately is not: a simulation. A branch is listed, never chosen; a loop is one step that
 * names where it returns to; a call is opened one level deep and named below that.
 */
object FlowStories {

    /** The schema of `flow.json`. Bumped when a consumer reading the old shape would be misled. */
    const val VERSION = 1

    /**
     * Steps one story may hold. A process whose branches never meet repeats what follows them once per
     * branch, and a few such gateways in a row multiply: without a ceiling, one unstructured model could
     * make the page it is embedded in unopenable. Far above any process a person drew on purpose.
     */
    internal const val MAX_STEPS = 4000

    /** `flow.json`: `{version, project, stories}`. */
    fun render(result: Map<String, Any?>, root: java.io.File): String = MiniJson.stringifyWithoutNulls(
        linkedMapOf("version" to VERSION, "project" to Fmt.projectName(root), "stories" to build(result)), 2,
    )

    /** One story per process, then one per case, each group by key. */
    fun build(result: Map<String, Any?>): List<Map<String, Any?>> {
        val processes = list(result["processes"]).associateBy { str(it["key"]) ?: "" }
        val starters = list(result["access"]).filter { it["action"] == "start" }
            .associateBy { "${it["modelType"]}:${it["model"]}" }
        val out = ArrayList<Map<String, Any?>>()
        for (p in list(result["processes"]).sortedBy { str(it["key"]) ?: "" }) {
            val walk = Walk(processes)
            out.add(story("process", p, starters, walk.scope(Proc(p), null, depth = 0), walk))
        }
        for (c in list(result["cases"]).sortedBy { str(it["key"]) ?: "" }) {
            val walk = Walk(processes)
            val plan = Dyn.mapOrNull(c["planModel"])
            if (plan == null) walk.warnings.add("the case has no plan model")
            out.add(story("case", c, starters, plan?.let { Case(c, walk).items(it) } ?: emptyList(), walk))
        }
        return out
    }

    private fun story(
        kind: String, model: Map<String, Any?>, starters: Map<String, Map<String, Any?>>,
        steps: List<Map<String, Any?>>, walk: Walk,
    ): Map<String, Any?> {
        val s = linkedMapOf<String, Any?>("kind" to kind, "key" to model["key"], "name" to str(model["name"]),
            "file" to model["file"])
        str(model["documentation"])?.let { s["documentation"] = it }
        // Only where the model names its starters: without them the engine lets anyone with access to
        // the app start it, which is a fact about the deployment and not one this file can state.
        starters["$kind:${model["key"]}"]?.let {
            s["startedBy"] = linkedMapOf("groups" to it["groups"], "users" to it["users"])
        }
        s["steps"] = steps
        if (walk.warnings.isNotEmpty()) s["warnings"] = walk.warnings.distinct()
        if (walk.truncated) s["truncated"] = true
        return s
    }

    // ---------------------------------------------------------------------------------------------
    // BPMN
    // ---------------------------------------------------------------------------------------------

    private class Flow(val to: String, val name: String?, val condition: String?, val isDefault: Boolean)

    /** One process, indexed for walking: its elements by id, the flows out of each, what hangs on each. */
    private class Proc(val p: Map<String, Any?>) {
        /** element id → (kind, record). `kind` is the element's tag, as the parser's buckets imply it. */
        val elements = LinkedHashMap<String, Pair<String, Map<String, Any?>>>()
        val out = HashMap<String, MutableList<Flow>>()
        val incoming = HashSet<String>()
        /** host id → the boundary events attached to it, in document order. */
        val boundary = HashMap<String, MutableList<String>>()

        init {
            fun add(bucket: String, kind: String?) {
                for (e in list(p[bucket])) {
                    val id = str(e["id"]) ?: continue
                    elements[id] = (kind ?: str(e["type"]) ?: "task") to e
                }
            }
            add("userTasks", "userTask"); add("serviceTasks", "serviceTask"); add("scriptTasks", "scriptTask")
            // After the service tasks, on purpose: a DMN service task is in both buckets under one id,
            // and "evaluates decision X" says more than "runs a service task of type dmn".
            add("ruleTasks", "decisionTask")
            add("callActivities", "callActivity"); add("subProcesses", "subProcess")
            add("events", null); add("gateways", null); add("otherTasks", null)
            for (f in list(p["flows"])) {
                val from = str(f["from"]) ?: continue
                val to = str(f["to"]) ?: continue
                out.getOrPut(from) { ArrayList() }.add(Flow(to, str(f["name"]), str(f["condition"]), f["default"] == true))
                incoming.add(to)
            }
            for ((id, e) in elements) {
                if (e.first == "boundaryEvent") str(e.second["attachedTo"])?.let { boundary.getOrPut(it) { ArrayList() }.add(id) }
            }
        }

        /**
         * Where the flow of a scope begins — the process itself ([scopeId] null) or one sub-process: its
         * start events, then the event sub-processes in it, which no flow enters. A scope without a start
         * event (an ad-hoc sub-process has none) begins at whatever in it nothing leads to.
         */
        fun entries(scopeId: String?): List<String> {
            val inScope = elements.filter { str(it.value.second["parent"]) == scopeId }
            val starts = inScope.filter { it.value.first == "startEvent" }.keys.toList()
            val eventSubs = inScope.filter { it.value.second["eventSubProcess"] == true }.keys
            if (starts.isNotEmpty() || scopeId == null) return starts + eventSubs
            // Only user tasks, events and sub-processes carry their scope; that is enough to open one.
            return inScope.filter { it.key !in incoming && it.value.first != "boundaryEvent" }.keys.toList()
        }
    }

    /** One story's walk: what it may resolve calls against, what it has to say afterwards. */
    private class Walk(val processes: Map<String, Map<String, Any?>>) {
        val warnings = ArrayList<String>()
        var truncated = false
        private var emitted = 0

        private fun spend(): Boolean {
            if (emitted >= MAX_STEPS) {
                if (!truncated) warnings.add("stopped after $MAX_STEPS steps: the model's branches do not meet again, so what follows them repeats")
                truncated = true
                return false
            }
            emitted++
            return true
        }

        /** Everything a scope of [proc] does: the walk from its first entry, then each further one under its own heading. */
        fun scope(proc: Proc, scopeId: String?, depth: Int): List<Map<String, Any?>> {
            val entries = proc.entries(scopeId)
            if (entries.isEmpty()) {
                if (scopeId == null) warnings.add("process ${proc.p["key"]} has no start event")
                return emptyList()
            }
            val out = ArrayList(walk(proc, entries.first(), emptySet(), emptySet(), depth))
            for (other in entries.drop(1)) {
                val (kind, rec) = proc.elements.getValue(other)
                // An event sub-process is its own step and says what triggers it; a second start event
                // is another way in, told apart from the first by its own trigger.
                if (kind == "subProcess") out.addAll(walk(proc, other, emptySet(), emptySet(), depth))
                else out.add(event("entry", rec).also { it["steps"] = walk(proc, other, emptySet(), emptySet(), depth) })
            }
            return out
        }

        /**
         * The steps from [from] on, up to — not including — a node in [stop]. [path] is what the walk has
         * passed through to get here: meeting one of those again is a loop, and is said as one step
         * instead of being walked a second time.
         */
        private fun walk(proc: Proc, from: String, stop: Set<String>, path: Set<String>, depth: Int): List<Map<String, Any?>> {
            val out = ArrayList<LinkedHashMap<String, Any?>>()
            val seen = HashSet(path)
            var cur: String? = from
            while (cur != null) {
                if (cur in stop) break
                val el = proc.elements[cur]
                if (el == null) { warnings.add("a sequence flow of ${proc.p["key"]} leads to `$cur`, which is not an element of it"); break }
                if (!seen.add(cur)) {
                    if (spend()) out.add(linkedMapOf("kind" to "loop", "id" to cur, "name" to str(el.second["name"])))
                    break
                }
                val (kind, rec) = el
                val flows = proc.out[cur].orEmpty()
                val isGateway = kind.endsWith("Gateway")
                // A gateway with one way out only joins what came in: the nesting already says that.
                if (!(isGateway && flows.size <= 1)) {
                    if (!spend()) break
                    out.add(describe(proc, kind, rec, depth))
                }
                for (b in proc.boundary[cur].orEmpty()) {
                    if (!spend()) break
                    val brec = proc.elements.getValue(b).second
                    val away = proc.out[b].orEmpty().map { it.to }
                    // Where the event's path gets back onto the line it left. From there on the story is
                    // the main one's: walked to the end, a reminder that leads back to the next task
                    // repeated everything after that task under the event.
                    val meet = if (away.isEmpty() || flows.isEmpty()) null else join(proc, away + flows.map { it.to }, seen)
                    val boundary = event("boundary", brec)
                    boundary["attachedTo"] = cur
                    // cancelActivity is true unless the model says otherwise
                    boundary["interrupting"] = str(brec["cancelActivity"]) != "false"
                    boundary["steps"] = away.flatMap { walk(proc, it, if (meet == null) stop else stop + meet, seen + b, depth) }
                    if (meet != null) boundary["rejoins"] = str(proc.elements[meet]?.second?.get("name")) ?: meet
                    out.add(boundary)
                }
                if (kind == "endEvent") break
                if (flows.size <= 1) { cur = flows.firstOrNull()?.to; continue }
                // Several ways out. Where they all meet again is where this walk carries on; each branch
                // holds what is its own up to there.
                val join = join(proc, flows.map { it.to }, seen)
                // the default path last, whatever the file's order: "otherwise" before the conditions it is
                // the alternative to reads backwards
                val branches = flows.sortedBy { it.isDefault }.map { f ->
                    val b = linkedMapOf<String, Any?>()
                    f.condition?.let { b["condition"] = it }
                    f.name?.let { b["name"] = it }
                    if (f.isDefault) b["default"] = true
                    b["steps"] = walk(proc, f.to, if (join == null) stop else stop + join, seen, depth)
                    b
                }
                if (isGateway) out.last()["branches"] = branches
                else if (spend()) {
                    // An activity with several outgoing flows forks without a gateway: every flow whose
                    // condition holds is taken, all of them when none has one.
                    out.add(linkedMapOf("kind" to "gateway", "id" to cur, "name" to null,
                        "gatewayKind" to (if (flows.any { it.condition != null }) "inclusive" else "parallel"),
                        "implicit" to true, "branches" to branches))
                }
                cur = join
            }
            return out
        }

        /**
         * The node where the paths starting at [targets] first all meet, or null when they never do —
         * one of them ends the process, say. "First" is the node the farthest branch reaches soonest.
         * Paths are not followed back into [path]: a branch that returns to an earlier step would
         * otherwise reach everything the other branches do, and the "join" would be the other branch.
         */
        private fun join(proc: Proc, targets: List<String>, path: Set<String>): String? {
            val reach = targets.map { t ->
                val dist = LinkedHashMap<String, Int>()
                val queue = ArrayDeque<String>()
                if (t !in path) { dist[t] = 0; queue.add(t) }
                while (queue.isNotEmpty()) {
                    val n = queue.removeFirst()
                    for (f in proc.out[n].orEmpty()) {
                        if (f.to in path || f.to in dist) continue
                        dist[f.to] = dist.getValue(n) + 1
                        queue.add(f.to)
                    }
                }
                dist
            }
            val common = reach.first().keys.filter { n -> reach.all { n in it } }
            return common.minWithOrNull(compareBy({ n -> reach.maxOf { it.getValue(n) } }, { n -> reach.sumOf { it.getValue(n) } }))
        }

        private fun describe(proc: Proc, kind: String, rec: Map<String, Any?>, depth: Int): LinkedHashMap<String, Any?> = when {
            kind == "startEvent" -> event("start", rec).also { s -> str(rec["formKey"])?.let { s["formKey"] = it } }
            kind == "endEvent" -> event("end", rec)
            kind.endsWith("Event") -> event("event", rec).also {
                it["catching"] = kind != "intermediateThrowEvent"
            }
            kind.endsWith("Gateway") -> step("gateway", rec).also { it["gatewayKind"] = kind.removeSuffix("Gateway") }
            kind == "userTask" -> step(kind, rec, "assignee", "candidateGroups", "candidateUsers", "formKey")
            // A case started from a process is a service task of type `case` in the XML and a call to a reader.
            kind == "serviceTask" && str(rec["type"]) == "case" && str(rec["caseDefinitionKey"]) != null ->
                call(rec, "case", str(rec["caseDefinitionKey"]), depth)
            kind == "serviceTask" -> step(kind, rec, "class", "delegateExpression", "expression", "type", "resultVariable",
                "serviceModelKey", "operationKey", "dataObjectKey", "agentModelKey", "channelKey").also { s ->
                // the event a send-event task publishes; `eventType` on a step is what kind of BPMN event it is
                str(rec["eventType"])?.let { s["eventKey"] = it }
            }
            kind == "scriptTask" -> step(kind, rec, "format", "resultVariable")
            kind == "decisionTask" -> step(kind, rec, "decisionRef")
            kind == "callActivity" -> call(rec, "process", str(rec["calledElement"]), depth,
                // an id names a deployed definition, not a model: matching it to a key would be a guess
                resolvable = !str(rec["calledElementType"]).equals("id", ignoreCase = true))
            kind == "subProcess" -> step(kind, rec).also {
                if (rec["eventSubProcess"] == true) it["eventSubProcess"] = true
                if (str(rec["type"]) != "subProcess") it["subProcessKind"] = rec["type"]
                it["steps"] = scope(proc, str(rec["id"]), depth)
            }
            else -> step(kind, rec)
        }

        /**
         * A call to another model. A process is opened here, once: at [depth] 0 its steps are part of
         * this story, below that the call is only named — its own story tells the rest, and a process
         * that calls itself would otherwise never end.
         */
        fun call(rec: Map<String, Any?>, targetKind: String, target: String?, depth: Int, resolvable: Boolean = true): LinkedHashMap<String, Any?> {
            val s = step("call", rec)
            s["targetKind"] = targetKind
            s["targetKey"] = target
            val proc = if (targetKind == "process" && resolvable && target != null) processes[target] else null
            when {
                target == null -> s["notOpened"] = "no target"
                // an expression is resolved by the engine at runtime; no model of that name exists
                "\${" in target || "#{" in target -> s["notOpened"] = "dynamic"
                targetKind != "process" -> s["notOpened"] = "case"
                proc == null -> s["notOpened"] = "not in this project"
                depth > 0 -> { s["targetName"] = str(proc["name"]); s["notOpened"] = "nested" }
                else -> { s["targetName"] = str(proc["name"]); s["steps"] = scope(Proc(proc), null, depth + 1) }
            }
            return s
        }
    }

    // ---------------------------------------------------------------------------------------------
    // CMMN
    // ---------------------------------------------------------------------------------------------

    /**
     * A case has no flow to walk: a plan item starts when its entry criterion is met, and nothing else
     * orders them. So a case's story is its plan tree with, on each item, what it waits for — in the
     * report's own words ([OverviewRenderer.criterionWhat]), so the page and the Markdown say the same
     * thing about the same sentry. Within a stage the items that wait for nothing come first and an item
     * follows the ones it waits for; that is a reading order, and the page says so.
     */
    private class Case(case: Map<String, Any?>, val walk: Walk) {
        private val tree = OverviewRenderer.CaseTree(case, list(case["sentries"]).mapNotNull { s -> str(s["id"])?.let { it to s } }.toMap())

        fun items(stage: Map<String, Any?>): List<Map<String, Any?>> {
            val children = list(stage["children"])
            val steps = children.map { item(stage, it) }
            // plan item id (what an on-part holds) → the sibling it names, by its place in the stage
            val byRef = HashMap<String, Int>()
            children.forEachIndexed { i, ch -> str(ch["id"])?.let { byRef.putIfAbsent(it, i) } }
            children.forEachIndexed { i, ch -> str(ch["planItemId"])?.let { byRef[it] = i } }
            val waitsFor = children.mapIndexed { i, ch ->
                OverviewRenderer.criteriaOf(stage, ch).filter { it["type"] == "entryCriterion" }
                    .flatMap { c -> Dyn.list(tree.sentries[str(c["sentryRef"])]?.get("onParts")) }
                    .mapNotNull { byRef[it as? String] }.filter { it != i }.toSet()
            }
            // Stable: document order, except that an item waits its turn behind what it waits for. A
            // cycle (two items each waiting for the other) releases the first one in the document.
            val left = children.indices.toMutableList()
            val placed = HashSet<Int>()
            val out = ArrayList<Map<String, Any?>>()
            while (left.isNotEmpty()) {
                val next = left.firstOrNull { placed.containsAll(waitsFor[it]) } ?: left.first()
                left.remove(next); placed.add(next); out.add(steps[next])
            }
            return out
        }

        private fun item(stage: Map<String, Any?>, ch: Map<String, Any?>): Map<String, Any?> {
            val type = str(ch["type"]) ?: "task"
            val s: LinkedHashMap<String, Any?> = when {
                ch.containsKey("children") -> step("stage", ch).also { it["steps"] = items(ch) }
                type == "humanTask" -> step("userTask", ch, "assignee", "candidateGroups", "candidateUsers", "formKey")
                type == "processTask" -> walk.call(ch, "process", str(ch["processRef"]), depth = 0)
                type == "caseTask" -> walk.call(ch, "case", str(ch["caseRef"]), depth = 0)
                type == "decisionTask" -> step(type, ch, "decisionRef")
                type in SERVICE_TYPES -> step("serviceTask", ch, "class", "delegateExpression", "expression", "resultVariable",
                    "serviceModelKey", "operationKey", "dataObjectKey", "agentModelKey", "formKey", "assignee", "candidateGroups").also {
                    (str(ch["taskType"]) ?: str(ch["serviceTaskType"]))?.let { t -> it["type"] = t }
                }
                // a milestone, an event listener, a plan item whose definition the file does not hold
                else -> step(type, ch)
            }
            val crits = OverviewRenderer.criteriaOf(stage, ch)
            fun said(gate: String) = crits.filter { it["type"] == gate }
                .map { OverviewRenderer.criterionWhat(it, tree).ifEmpty { "(its sentry is not in the model)" } }
            said("entryCriterion").takeIf { it.isNotEmpty() }?.let { s["entry"] = it }
            said("exitCriterion").takeIf { it.isNotEmpty() }?.let { s["exit"] = it }
            val rules = Dyn.map(ch["rules"])
            for ((rule, key) in listOf("manualActivationRule" to "manualActivation", "repetitionRule" to "repetition", "requiredRule" to "required")) {
                if (rules.containsKey(rule)) s[key] = true
            }
            return s
        }
    }

    private val SERVICE_TYPES = setOf("task", "serviceTask", "humanTaskWithService")

    // ---------------------------------------------------------------------------------------------

    /** `{kind, id, name}` plus those of [keys] the record has a value for — and a comma list as a list. */
    private fun step(kind: String, rec: Map<String, Any?>, vararg keys: String): LinkedHashMap<String, Any?> {
        val s = linkedMapOf<String, Any?>("kind" to kind, "id" to rec["id"], "name" to str(rec["name"]))
        for (k in keys) {
            val v = str(rec[k]) ?: continue
            s[k] = if (k == "candidateGroups" || k == "candidateUsers") v.split(',').map { it.trim() }.filter { it.isNotEmpty() } else v
        }
        return s
    }

    /** An event step: what kind of event it is (`timer`, `message`, …; absent for a plain one) and what it names. */
    private fun event(kind: String, rec: Map<String, Any?>): LinkedHashMap<String, Any?> {
        val s = step(kind, rec)
        str(rec["def"])?.let { s["eventType"] = it }
        // `ref` is the name the shared message/signal node is keyed by; `value` the definition's own id
        (str(rec["ref"]) ?: str(rec["value"]))?.let { s["eventRef"] = it }
        return s
    }

    private fun str(v: Any?): String? = (v as? String)?.trim()?.ifEmpty { null }

    private fun list(v: Any?): List<Map<String, Any?>> = Dyn.list(v).mapNotNull { Dyn.mapOrNull(it) }
}
