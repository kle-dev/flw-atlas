package com.flowable.atlas.flow

/**
 * Turns `Atlas.extract()`'s raw output into ordered, branch-aware [FlowStory]s — one per **startable**
 * process or case.
 *
 * Startable set: filtered from the `access` bucket where `action == "start"` — the exact same predicate
 * the existing `SummaryRenderer` / `OverviewRenderer` / `ClaudeRenderer` / explorer HTML use for their
 * "Entry points — who can start what" listings, so this feature can never disagree with them.
 *
 * Consumes:
 *   - `result["processes"]`  — BPMN process definitions with the new `flows` list added in v1
 *     (see `BackendModelParsers.kt`; the change is purely additive).
 *   - `result["cases"]`      — CMMN case definitions (Phase C).
 *   - `result["access"]`     — the startable filter.
 *
 * BPMN uses a simple "shared visited set" heuristic for gateway convergence — good enough for real
 * branching shapes; pathological flows emit a `meta.warnings` entry and terminate the branch. CMMN
 * cases render as a non-linear list of plan items (with recursive stages) — see [buildCaseStory].
 */
object FlowTraversal {

    @Suppress("UNCHECKED_CAST")
    fun traverseAll(result: Map<String, Any?>): List<FlowStory> {
        val stories = ArrayList<FlowStory>()

        // 1. Which entry points are startable?
        val access = result["access"] as? List<Map<String, Any?>> ?: emptyList()
        val startEntries = access.filter { it["action"] == "start" }

        // 2. Index processes and cases by key so cross-references (call activities, processTask,
        //    caseTask) can resolve their targets.
        val processes = result["processes"] as? List<Map<String, Any?>> ?: emptyList()
        val processesByKey = processes.associateBy { it["key"] as? String ?: "" }
        val cases = result["cases"] as? List<Map<String, Any?>> ?: emptyList()
        val casesByKey = cases.associateBy { it["key"] as? String ?: "" }

        // 3. BPMN processes
        for (a in startEntries.filter { it["modelType"] == "process" }.sortedBy { it["model"] as? String ?: "" }) {
            val key = a["model"] as? String ?: continue
            val proc = processesByKey[key] ?: continue
            stories.add(buildProcessStory(proc, startedByOf(a), processesByKey))
        }

        // 4. CMMN cases
        for (a in startEntries.filter { it["modelType"] == "case" }.sortedBy { it["model"] as? String ?: "" }) {
            val key = a["model"] as? String ?: continue
            val caseObj = casesByKey[key] ?: continue
            stories.add(buildCaseStory(caseObj, startedByOf(a), casesByKey, processesByKey))
        }

        return stories
    }

    @Suppress("UNCHECKED_CAST")
    private fun startedByOf(access: Map<String, Any?>) = StartedBy(
        groups = (access["groups"] as? List<String>) ?: emptyList(),
        users = (access["users"] as? List<String>) ?: emptyList(),
    )

    // -----------------------------------------------------------------------
    // BPMN traversal
    // -----------------------------------------------------------------------

    private class BpmnContext(
        val process: Map<String, Any?>,
        val processesByKey: Map<String, Map<String, Any?>>,
        val warnings: MutableList<String> = ArrayList(),
    ) {
        val elementsById: Map<String, ParsedElement> = indexElements(process)
        val outgoing: Map<String, List<Flow>> = indexOutgoing(process)
    }

    private data class ParsedElement(
        val id: String,
        val name: String?,
        /** Bucket-of-origin tag: "userTask" / "serviceTask" / "gateway" / "startEvent" / "endEvent" /
         *  "callActivity" / "subProcess" / "event" / "receiveTask" / ... */
        val kind: String,
        val raw: Map<String, Any?>,
    )

    private data class Flow(val id: String?, val from: String, val to: String, val condition: String?)

    @Suppress("UNCHECKED_CAST")
    private fun indexElements(process: Map<String, Any?>): Map<String, ParsedElement> {
        val out = LinkedHashMap<String, ParsedElement>()
        fun add(bucket: String?, list: Any?, kindFromTag: Boolean = false) {
            val l = list as? List<Map<String, Any?>> ?: return
            for (e in l) {
                val id = e["id"] as? String ?: continue
                val name = e["name"] as? String
                val kind = when {
                    kindFromTag -> e["type"] as? String ?: bucket ?: "task"
                    bucket != null -> bucket
                    else -> "task"
                }
                out[id] = ParsedElement(id, name, kind, e)
            }
        }
        add("userTask", process["userTasks"])
        add("serviceTask", process["serviceTasks"])
        add("scriptTask", process["scriptTasks"])
        add("ruleTask", process["ruleTasks"])
        add("callActivity", process["callActivities"])
        add("subProcess", process["subProcesses"])
        // events carry their `type` tag (startEvent / endEvent / intermediateThrowEvent / boundaryEvent / …)
        add(null, process["events"], kindFromTag = true)
        // gateways carry their `type` tag too (exclusiveGateway / parallelGateway / …)
        add(null, process["gateways"], kindFromTag = true)
        // otherTasks: receiveTask / sendTask / manualTask / task
        add(null, process["otherTasks"], kindFromTag = true)
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun indexOutgoing(process: Map<String, Any?>): Map<String, List<Flow>> {
        val flows = process["flows"] as? List<Map<String, Any?>> ?: emptyList()
        val out = LinkedHashMap<String, ArrayList<Flow>>()
        for (f in flows) {
            val src = f["from"] as? String ?: continue
            val tgt = f["to"] as? String ?: continue
            out.getOrPut(src) { ArrayList() }
                .add(Flow(id = f["id"] as? String, from = src, to = tgt, condition = f["condition"] as? String))
        }
        return out
    }

    private fun buildProcessStory(
        process: Map<String, Any?>,
        startedBy: StartedBy,
        processesByKey: Map<String, Map<String, Any?>>,
    ): FlowStory {
        val ctx = BpmnContext(process, processesByKey)
        val startEvents = ctx.elementsById.values.filter { it.kind == "startEvent" }
        val entry = startEvents.firstOrNull()
        val steps: List<Step> = if (entry == null) {
            ctx.warnings.add("no <startEvent> found in process '${process["key"]}'")
            emptyList()
        } else {
            walkFrom(entry.id, ctx, visited = HashSet())
        }
        return FlowStory(
            kind = "process",
            key = process["key"] as? String ?: "",
            name = process["name"] as? String,
            file = process["file"] as? String ?: "",
            startedBy = startedBy,
            body = ProcessBody(steps),
            meta = Meta(warnings = ctx.warnings.toList()),
        )
    }

    /**
     * Walk the flow from [fromId] forward, emitting Steps in order.
     * [visited] guards against revisiting nodes across a walk (including across branch scopes) —
     * this is the simple cycle-guard used in v1.
     */
    private fun walkFrom(fromId: String, ctx: BpmnContext, visited: MutableSet<String>): List<Step> {
        val out = ArrayList<Step>()
        var current: String? = fromId
        while (current != null) {
            if (!visited.add(current)) {
                // Convergence or cycle: another walk already emitted this node upstream in the tree.
                // v1 heuristic: stop this branch here. The shared continuation lives in the enclosing
                // scope (whatever emitted the node the first time).
                return out
            }
            val elem = ctx.elementsById[current]
            if (elem == null) {
                ctx.warnings.add("unknown element id '$current' referenced by a sequence flow")
                return out
            }
            val outFlows = ctx.outgoing[current].orEmpty()

            when {
                elem.kind == "startEvent" -> out.add(makeStart(elem))
                elem.kind == "endEvent" -> {
                    out.add(makeEnd(elem))
                    return out    // end terminates the walk regardless of outgoing flows
                }
                elem.kind.endsWith("Gateway") || outFlows.size > 1 -> {
                    // Real gateway OR any node with >1 outgoing flows (implicit gateway).
                    out.add(makeGateway(elem, outFlows, ctx, visited))
                    return out    // branches consume the rest; whatever follows the join lives in the
                                  // enclosing scope, and v1 uses visited-set-sharing to reach it.
                }
                elem.kind == "callActivity" -> out.add(makeCall(elem, ctx))
                else -> out.add(makeActivity(elem))
            }

            // Linear continuation: single outgoing flow (or zero — walk ends).
            current = outFlows.singleOrNull()?.to
        }
        return out
    }

    // ---------- Step constructors ----------

    private fun makeStart(elem: ParsedElement): Step {
        val def = elem.raw["def"] as? String   // "message" | "timer" | "signal" | "conditional" | "error" | null
        val value = elem.raw["value"] as? String
        val formKey = elem.raw["formKey"] as? String   // populated by parser only if present
        return StartStep(
            id = elem.id,
            name = elem.name,
            trigger = def ?: "none",
            triggerRef = if (def != null) value else null,
            formKey = formKey,
        )
    }

    private fun makeEnd(elem: ParsedElement): Step {
        val def = elem.raw["def"] as? String
        return EndStep(
            id = elem.id,
            name = elem.name,
            reason = def ?: "none",
            reasonRef = elem.raw["value"] as? String,
        )
    }

    private fun makeActivity(elem: ParsedElement): Step = when (elem.kind) {
        "userTask" -> ActivityStep(
            id = elem.id, name = elem.name, kind = "userTask",
            assignee = elem.raw["assignee"] as? String,
            candidateGroups = splitCsv(elem.raw["candidateGroups"] as? String),
            candidateUsers = splitCsv(elem.raw["candidateUsers"] as? String),
            formKey = elem.raw["formKey"] as? String,
        )
        "serviceTask" -> ActivityStep(
            id = elem.id, name = elem.name, kind = "serviceTask",
            delegate = Delegate(
                clazz = elem.raw["class"] as? String,
                expression = elem.raw["expression"] as? String,
                delegateExpression = elem.raw["delegateExpression"] as? String,
                type = elem.raw["type"] as? String,
                resultVariable = elem.raw["resultVariable"] as? String,
                serviceModelKey = elem.raw["serviceModelKey"] as? String,
                operationKey = elem.raw["operationKey"] as? String,
            ).nullIfEmpty(),
        )
        "scriptTask" -> ActivityStep(
            id = elem.id, name = elem.name, kind = "scriptTask",
            script = ScriptInfo(
                language = elem.raw["format"] as? String,
                resultVariable = elem.raw["resultVariable"] as? String,
            ),
        )
        "ruleTask" -> ActivityStep(
            id = elem.id, name = elem.name, kind = "businessRuleTask",
            decisionRef = elem.raw["decisionRef"] as? String,
        )
        // otherTasks — receiveTask / sendTask / manualTask / task — the parser stored the tag in "type"
        else -> ActivityStep(id = elem.id, name = elem.name, kind = elem.kind)
    }

    @Suppress("UNCHECKED_CAST")
    private fun makeCall(elem: ParsedElement, ctx: BpmnContext): Step {
        val targetKey = elem.raw["calledElement"] as? String
        val targetKind: String
        val inline: Inline
        val targetName: String?
        val targetProc = targetKey?.let { ctx.processesByKey[it] }
        if (targetProc != null) {
            targetKind = "process"
            targetName = targetProc["name"] as? String
            // One-level inlining (Q2): expand the target as a fresh walk with its own visited set —
            // the inlined walk should not share our visited set, or a repeated element id across
            // processes would incorrectly short-circuit.
            val innerCtx = BpmnContext(targetProc, ctx.processesByKey)
            val innerEntry = innerCtx.elementsById.values.firstOrNull { it.kind == "startEvent" }
            val innerSteps = if (innerEntry != null) {
                walkFrom(innerEntry.id, innerCtx, visited = HashSet())
            } else {
                ctx.warnings.add("call activity '${elem.id}' target process '$targetKey' has no startEvent")
                emptyList()
            }
            inline = InlineResolved(innerSteps)
        } else {
            targetKind = if (targetKey == null) "unresolved" else "external"
            targetName = null
            inline = InlineUnresolved(reason = "unresolvable")
        }
        return CallStep(
            id = elem.id, name = elem.name,
            callType = "callActivity",
            targetKey = targetKey,
            targetName = targetName,
            targetKind = targetKind,
            inline = inline,
        )
    }

    private fun makeGateway(
        elem: ParsedElement,
        outFlows: List<Flow>,
        ctx: BpmnContext,
        visited: MutableSet<String>,
    ): Step {
        // Kind stored by the parser is the raw tag ("exclusiveGateway", "parallelGateway", …).
        val gatewayKind = when (elem.kind) {
            "exclusiveGateway" -> "exclusive"
            "parallelGateway" -> "parallel"
            "inclusiveGateway" -> "inclusive"
            "eventBasedGateway" -> "eventBased"
            "complexGateway" -> "complex"
            else -> "exclusive"     // implicit gateway (non-gateway element with >1 outgoing flows)
        }
        val branches = outFlows.map { f ->
            val branchSteps = walkFrom(f.to, ctx, visited)
            Branch(
                condition = f.condition,
                isDefault = f.condition.isNullOrEmpty(),
                steps = branchSteps,
            )
        }
        return GatewayStep(
            id = elem.id,
            name = elem.name,
            gatewayKind = gatewayKind,
            branches = branches,
        )
    }

    // -----------------------------------------------------------------------
    // CMMN traversal
    // -----------------------------------------------------------------------

    /**
     * Case-wide indexes for translating sentry references into human descriptions and predecessor
     * step ids. Sentries reference plan items by planItem id ([Map.get] "planItemId" from the raw
     * parser output), whereas the emitted Step.id is the task/stage definition id; we need both
     * directions.
     */
    @Suppress("UNCHECKED_CAST")
    private class CmmnResolver(caseObj: Map<String, Any?>) {
        /** planItem id  →  child def id (== emitted Step.id). */
        private val defIdByPlanItem: Map<String, String>
        /** child def id  →  human-visible name (falls back to the def id). */
        private val nameByDefId: Map<String, String>
        /** sentry id  →  raw sentry map (`{id, condition, onParts}`). */
        private val sentriesById: Map<String, Map<String, Any?>>

        init {
            val defByPlan = HashMap<String, String>()
            val nameById = HashMap<String, String>()
            fun walk(node: Map<String, Any?>?) {
                if (node == null) return
                val pid = node["planItemId"] as? String
                val id = node["id"] as? String
                if (pid != null && id != null) defByPlan[pid] = id
                if (id != null) nameById[id] = (node["name"] as? String)?.takeIf { it.isNotBlank() } ?: id
                (node["children"] as? List<Map<String, Any?>>)?.forEach { walk(it) }
            }
            walk(caseObj["planModel"] as? Map<String, Any?>)
            defIdByPlanItem = defByPlan
            nameByDefId = nameById
            sentriesById = ((caseObj["sentries"] as? List<Map<String, Any?>>).orEmpty()
                .mapNotNull { s -> (s["id"] as? String)?.let { it to s } }
                .toMap())
        }

        /**
         * For a plan item emitted as [stepId] and the criteria list from its enclosing stage,
         * returns human descriptions of every matching entry criterion — one string per criterion,
         * e.g. *"when 'Review order' completes"* or *"when \${amount > 100} holds"*.
         */
        fun entryCriteriaFor(stepId: String, stageCriteria: List<Map<String, Any?>>): List<String>? =
            criteriaFor(stepId, "entryCriterion", stageCriteria)

        fun exitCriteriaFor(stepId: String, stageCriteria: List<Map<String, Any?>>): List<String>? =
            criteriaFor(stepId, "exitCriterion", stageCriteria)

        /** Predecessor step ids for the topological sort — resolved from a criterion's sentry.onParts. */
        fun predecessorStepIdsFor(stepId: String, stageCriteria: List<Map<String, Any?>>): Set<String> {
            val out = LinkedHashSet<String>()
            for (c in matchingCriteria(stepId, "entryCriterion", stageCriteria)) {
                val sentry = sentriesById[c["sentryRef"] as? String] ?: continue
                for (p in (sentry["onParts"] as? List<String>).orEmpty()) {
                    defIdByPlanItem[p]?.let { out.add(it) }
                }
            }
            return out
        }

        private fun criteriaFor(
            stepId: String, type: String, stageCriteria: List<Map<String, Any?>>,
        ): List<String>? {
            val descs = matchingCriteria(stepId, type, stageCriteria).mapNotNull { c ->
                val sentry = sentriesById[c["sentryRef"] as? String]
                    ?: return@mapNotNull "when a sentry fires"
                describeSentry(sentry)
            }
            return descs.ifEmpty { null }
        }

        private fun matchingCriteria(
            stepId: String, type: String, stageCriteria: List<Map<String, Any?>>,
        ): List<Map<String, Any?>> = stageCriteria.filter {
            // criteria[].planItem is the planItem's `name` if set else its `definitionRef` (== stepId
            // in the common no-name case). Match on either.
            it["type"] == type && (it["planItem"] == stepId || nameByDefId[stepId] == it["planItem"])
        }

        private fun describeSentry(sentry: Map<String, Any?>): String {
            val onParts = (sentry["onParts"] as? List<String>).orEmpty()
                .mapNotNull { pid -> defIdByPlanItem[pid]?.let { nameByDefId[it] ?: it } }
            val cond = (sentry["condition"] as? String)?.takeIf { it.isNotBlank() }?.trim()
            val bits = ArrayList<String>()
            when (onParts.size) {
                0 -> {}
                1 -> bits.add("'${onParts[0]}' completes")
                else -> bits.add("all of ${onParts.joinToString { "'$it'" }} complete")
            }
            if (cond != null) bits.add("`$cond` is true")
            return "when " + if (bits.isEmpty()) "the sentry fires" else bits.joinToString(" and ")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildCaseStory(
        caseObj: Map<String, Any?>,
        startedBy: StartedBy,
        casesByKey: Map<String, Map<String, Any?>>,
        processesByKey: Map<String, Map<String, Any?>>,
    ): FlowStory {
        val warnings = ArrayList<String>()
        val resolver = CmmnResolver(caseObj)
        val plan = caseObj["planModel"] as? Map<String, Any?>
        val topSteps: List<Step> = if (plan != null) {
            val kids = plan["children"] as? List<Map<String, Any?>> ?: emptyList()
            val stageCriteria = (plan["criteria"] as? List<Map<String, Any?>>).orEmpty()
            val raw = kids.map { convertCmmnChild(it, warnings, casesByKey, processesByKey, resolver, stageCriteria) }
            // Topologically order by entry-criteria dependencies — items with no predecessors first
            // (active on case entry), then items whose predecessors are already emitted. Retains
            // original XML order within a "level". Any leftover items (cycles or unresolved deps)
            // append at the end so nothing is silently dropped.
            topoSort(raw, kids, resolver, stageCriteria)
        } else {
            warnings.add("case '${caseObj["key"]}' has no <casePlanModel>")
            emptyList()
        }
        val milestones = (caseObj["milestones"] as? List<Map<String, Any?>>).orEmpty().map {
            linkedMapOf<String, Any?>("id" to it["id"], "name" to it["name"])
        }
        return FlowStory(
            kind = "case",
            key = caseObj["key"] as? String ?: "",
            name = caseObj["name"] as? String,
            file = caseObj["file"] as? String ?: "",
            startedBy = startedBy,
            body = CaseBody(planItems = topSteps, milestones = milestones),
            meta = Meta(warnings = warnings.toList()),
        )
    }

    /** Kahn-style topological order. Items with no predecessors (or predecessors not in the same
     *  scope) come first — original list order preserved among ties. */
    @Suppress("UNCHECKED_CAST")
    private fun topoSort(
        steps: List<Step>,
        rawChildren: List<Map<String, Any?>>,
        resolver: CmmnResolver,
        stageCriteria: List<Map<String, Any?>>,
    ): List<Step> {
        if (steps.size <= 1) return steps
        val byId = steps.associateBy { it.id }
        val predecessors = steps.associate { s ->
            s.id to resolver.predecessorStepIdsFor(s.id, stageCriteria).filter { byId.containsKey(it) }.toSet()
        }
        val remaining = ArrayDeque(steps)
        val placed = LinkedHashSet<String>()
        val out = ArrayList<Step>()
        var stalled = 0
        while (remaining.isNotEmpty()) {
            val next = remaining.first()
            if (predecessors[next.id]!!.all { it in placed }) {
                out.add(next)
                placed.add(next.id)
                remaining.removeFirst()
                stalled = 0
            } else {
                remaining.removeFirst()
                remaining.addLast(next)   // defer to a later pass
                stalled++
                if (stalled >= remaining.size) break   // cycle or unresolved — emit remainder as-is
            }
        }
        out.addAll(remaining)
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun convertCmmnChild(
        child: Map<String, Any?>,
        warnings: MutableList<String>,
        casesByKey: Map<String, Map<String, Any?>>,
        processesByKey: Map<String, Map<String, Any?>>,
        resolver: CmmnResolver,
        parentStageCriteria: List<Map<String, Any?>>,
    ): Step {
        val id = child["id"] as? String ?: "?"
        val name = child["name"] as? String
        val entryC = resolver.entryCriteriaFor(id, parentStageCriteria)
        val exitC = resolver.exitCriteriaFor(id, parentStageCriteria)
        val manual = ((child["rules"] as? Map<String, Any?>)?.containsKey("manualActivationRule")) == true
        return when (child["type"] as? String) {
            "stage", "planFragment", "casePlanModel" -> {
                val kidsRaw = (child["children"] as? List<Map<String, Any?>>).orEmpty()
                val innerCriteria = (child["criteria"] as? List<Map<String, Any?>>).orEmpty()
                val kids = kidsRaw.map { convertCmmnChild(it, warnings, casesByKey, processesByKey, resolver, innerCriteria) }
                val orderedKids = topoSort(kids, kidsRaw, resolver, innerCriteria)
                StageStep(
                    id = id, name = name,
                    autoComplete = child["autoComplete"] == "true",
                    steps = orderedKids,
                    entryCriteria = entryC, exitCriteria = exitC, manualActivation = manual,
                )
            }
            "humanTask" -> ActivityStep(
                id = id, name = name, kind = "userTask",
                assignee = child["assignee"] as? String,
                candidateGroups = splitCsv(child["candidateGroups"] as? String),
                candidateUsers = splitCsv(child["candidateUsers"] as? String),
                formKey = child["formKey"] as? String,
                entryCriteria = entryC, exitCriteria = exitC, manualActivation = manual,
            )
            "processTask" -> {
                val ref = child["processRef"] as? String
                val target = ref?.let { processesByKey[it] }
                val inline: Inline = if (target != null) {
                    val innerCtx = BpmnContext(target, processesByKey)
                    val entry = innerCtx.elementsById.values.firstOrNull { it.kind == "startEvent" }
                    if (entry != null) InlineResolved(walkFrom(entry.id, innerCtx, HashSet()))
                    else { warnings.add("processTask '$id' target '$ref' has no startEvent"); InlineUnresolved("unresolvable") }
                } else InlineUnresolved(if (ref == null) "unresolvable" else "external")
                CallStep(
                    id = id, name = name, callType = "callActivity",
                    targetKey = ref,
                    targetName = target?.get("name") as? String,
                    targetKind = if (target != null) "process" else "external",
                    inline = inline,
                    entryCriteria = entryC, exitCriteria = exitC, manualActivation = manual,
                )
            }
            "caseTask" -> {
                val ref = child["caseRef"] as? String
                val target = ref?.let { casesByKey[it] }
                CallStep(
                    id = id, name = name, callType = "callActivity",
                    targetKey = ref,
                    targetName = target?.get("name") as? String,
                    // Only one level of inlining (Q2); a caseTask target that's itself a case doesn't
                    // participate in BPMN process traversal, so the deeper hop stays as a reference.
                    targetKind = if (target != null) "case" else "external",
                    inline = InlineUnresolved(reason = if (target != null) "deeper-than-one-level" else "external"),
                    entryCriteria = entryC, exitCriteria = exitC, manualActivation = manual,
                )
            }
            "decisionTask" -> ActivityStep(
                id = id, name = name, kind = "businessRuleTask",
                decisionRef = child["decisionRef"] as? String,
                entryCriteria = entryC, exitCriteria = exitC, manualActivation = manual,
            )
            "task", "serviceTask", "humanTaskWithService" -> {
                val serviceRef = child["serviceModelKey"] as? String
                val opKey = child["operationKey"] as? String
                val delegate = Delegate(
                    clazz = child["class"] as? String,
                    expression = child["expression"] as? String,
                    delegateExpression = child["delegateExpression"] as? String,
                    // Surface service+operation mapping as the delegate's type when nothing else is set —
                    // this is Flowable's most common wiring for CMMN service/task steps.
                    type = child["serviceTaskType"] as? String
                        ?: if (serviceRef != null && opKey != null) "$serviceRef.$opKey" else null,
                    serviceModelKey = serviceRef,
                    operationKey = opKey,
                ).nullIfEmpty()
                ActivityStep(
                    id = id, name = name, kind = "serviceTask",
                    delegate = delegate,
                    formKey = child["formKey"] as? String,
                    assignee = child["assignee"] as? String,
                    candidateGroups = splitCsv(child["candidateGroups"] as? String),
                    entryCriteria = entryC, exitCriteria = exitC, manualActivation = manual,
                )
            }
            else -> {
                val t = child["type"] as? String
                if (t != null && t != "planItem(?)") warnings.add("unrecognised CMMN plan-item type '$t' (id=$id)")
                ActivityStep(
                    id = id, name = name, kind = t ?: "task",
                    entryCriteria = entryC, exitCriteria = exitC, manualActivation = manual,
                )
            }
        }
    }

    // ---------- utilities ----------

    private fun splitCsv(s: String?): List<String>? {
        if (s.isNullOrBlank()) return null
        return s.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Returns null if every field of the delegate is null/blank — keeps the JSON tidy for tasks
     *  without a resolvable delegate (e.g. Flowable Design tasks configured elsewhere). */
    private fun Delegate.nullIfEmpty(): Delegate? =
        if (clazz.isNullOrBlank() && expression.isNullOrBlank() && delegateExpression.isNullOrBlank()
            && type.isNullOrBlank() && resultVariable.isNullOrBlank()
            && serviceModelKey.isNullOrBlank() && operationKey.isNullOrBlank()) null else this
}
