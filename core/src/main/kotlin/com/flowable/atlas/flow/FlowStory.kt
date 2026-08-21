package com.flowable.atlas.flow

/**
 * FlowStory — the ordered, branch-aware business narrative of one startable BPMN process or CMMN case.
 *
 * Consumed by:
 *   - `FlowJsonRenderer` → `<project>.flow.json` (standalone artifact)
 *   - `ExplorerHtmlRenderer` → embedded JSON blob in `explorer.html` (Storyline view)
 *
 * Produced by [FlowTraversal] from `Atlas.extract()`'s raw `processes` bucket (and `cases` in Phase C).
 *
 * See `features/business-workflow-narrative/flow-story-schema.md` for the schema definition — this file
 * is the Kotlin realisation of that schema. `toMap()` produces the on-wire JSON shape.
 */

data class FlowStoryFile(
    val project: String,
    val stories: List<FlowStory>,
    val version: Int = 1,
) {
    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "version" to version,
        "project" to project,
        "stories" to stories.map { it.toMap() },
    )
}

/** One narrative per startable entry point (BPMN `<process>` or CMMN `<case>`). */
data class FlowStory(
    val kind: String,             // "process" | "case"
    val key: String,
    val name: String?,
    val file: String,
    val startedBy: StartedBy,
    val body: Body,
    val meta: Meta = Meta(),
) {
    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "kind" to kind,
        "key" to key,
        "name" to name,
        "file" to file,
        "startedBy" to startedBy.toMap(),
        "body" to body.toMap(),
        "meta" to meta.toMap(),
    )
}

data class StartedBy(
    val groups: List<String>,
    val users: List<String>,
) {
    val unrestricted: Boolean get() = groups.isEmpty() && users.isEmpty()
    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "groups" to groups,
        "users" to users,
        "unrestricted" to unrestricted,
    )
}

data class Meta(
    val warnings: List<String> = emptyList(),
    val truncated: Boolean = false,
) {
    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "warnings" to warnings,
        "truncated" to truncated,
    )
}

/** BPMN or CMMN body — polymorphic via the top-level `FlowStory.kind`. */
sealed interface Body {
    fun toMap(): LinkedHashMap<String, Any?>
}

/** BPMN: ordered top-to-bottom walk from start events to end events. */
data class ProcessBody(val steps: List<Step>) : Body {
    override fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "kind" to "process",
        "steps" to steps.map { it.toMap() },
    )
}

/** CMMN: stages + case-level plan items + milestones (event-based, non-linear). Phase C. */
data class CaseBody(
    val planItems: List<Step> = emptyList(),
    val milestones: List<Map<String, Any?>> = emptyList(),
) : Body {
    override fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "kind" to "case",
        "planItems" to planItems.map { it.toMap() },
        "milestones" to milestones,
    )
}

// -----------------------------------------------------------------------
// Steps
// -----------------------------------------------------------------------

sealed interface Step {
    val id: String
    val name: String?
    fun toMap(): LinkedHashMap<String, Any?>
}

data class StartStep(
    override val id: String,
    override val name: String?,
    val trigger: String = "none",   // "none" | "message" | "timer" | "signal" | "conditional" | "error"
    val triggerRef: String? = null,
    val formKey: String? = null,
) : Step {
    override fun toMap(): LinkedHashMap<String, Any?> {
        val m = linkedMapOf<String, Any?>("kind" to "start", "id" to id, "name" to name, "trigger" to trigger)
        if (triggerRef != null) m["triggerRef"] = triggerRef
        if (formKey != null) m["formKey"] = formKey
        return m
    }
}

data class EndStep(
    override val id: String,
    override val name: String?,
    val reason: String = "none",    // "none" | "message" | "signal" | "error" | "escalation" | "cancel" | "terminate"
    val reasonRef: String? = null,
) : Step {
    override fun toMap(): LinkedHashMap<String, Any?> {
        val m = linkedMapOf<String, Any?>("kind" to "end", "id" to id, "name" to name, "reason" to reason)
        if (reasonRef != null) m["reasonRef"] = reasonRef
        return m
    }
}

/**
 * Any "does something" activity: userTask, serviceTask, scriptTask, businessRuleTask, receiveTask,
 * sendTask, manualTask, task. The [kind] field discriminates. Field population is by activity type;
 * unused fields stay null and are omitted from the JSON.
 */
data class ActivityStep(
    override val id: String,
    override val name: String?,
    val kind: String,               // "userTask" | "serviceTask" | ... — the activity-specific discriminator
    // userTask
    val assignee: String? = null,
    val candidateGroups: List<String>? = null,
    val candidateUsers: List<String>? = null,
    val formKey: String? = null,
    // serviceTask / sendTask
    val delegate: Delegate? = null,
    // businessRuleTask / DMN service task
    val decisionRef: String? = null,
    // scriptTask
    val script: ScriptInfo? = null,
    // CMMN plan-item metadata — only populated when this step is a CMMN plan item
    val entryCriteria: List<String>? = null,
    val exitCriteria: List<String>? = null,
    val manualActivation: Boolean = false,
) : Step {
    override fun toMap(): LinkedHashMap<String, Any?> {
        val m = linkedMapOf<String, Any?>("kind" to kind, "id" to id, "name" to name)
        if (assignee != null) m["assignee"] = assignee
        if (candidateGroups != null) m["candidateGroups"] = candidateGroups
        if (candidateUsers != null) m["candidateUsers"] = candidateUsers
        if (formKey != null) m["formKey"] = formKey
        if (delegate != null) m["delegate"] = delegate.toMap()
        if (decisionRef != null) m["decisionRef"] = decisionRef
        if (script != null) m["script"] = script.toMap()
        if (!entryCriteria.isNullOrEmpty()) m["entryCriteria"] = entryCriteria
        if (!exitCriteria.isNullOrEmpty()) m["exitCriteria"] = exitCriteria
        if (manualActivation) m["manualActivation"] = true
        return m
    }
}

data class Delegate(
    val clazz: String? = null,
    val expression: String? = null,
    val delegateExpression: String? = null,
    val type: String? = null,
    val resultVariable: String? = null,
    // Service-registry wiring resolved by the parser — lets the explorer link a service-registry
    // task in the flow view straight to the `serviceOperation:<serviceModelKey>#<operationKey>` node.
    val serviceModelKey: String? = null,
    val operationKey: String? = null,
) {
    fun toMap(): LinkedHashMap<String, Any?> {
        val m = linkedMapOf<String, Any?>()
        if (clazz != null) m["class"] = clazz
        if (expression != null) m["expression"] = expression
        if (delegateExpression != null) m["delegateExpression"] = delegateExpression
        if (type != null) m["type"] = type
        if (resultVariable != null) m["resultVariable"] = resultVariable
        if (serviceModelKey != null) m["serviceModelKey"] = serviceModelKey
        if (operationKey != null) m["operationKey"] = operationKey
        return m
    }
}

data class ScriptInfo(
    val language: String?,
    val resultVariable: String? = null,
) {
    fun toMap(): LinkedHashMap<String, Any?> {
        val m = linkedMapOf<String, Any?>("language" to language)
        if (resultVariable != null) m["resultVariable"] = resultVariable
        return m
    }
}

/**
 * Diverging point: exclusive / inclusive / parallel / event-based / complex.
 * Convergence (joins) live implicitly in the enclosing scope — see spec: whatever comes AFTER the
 * gateway in the parent [ProcessBody.steps] is the shared continuation; each [Branch.steps] contains
 * only what is unique to that branch.
 */
data class GatewayStep(
    override val id: String,
    override val name: String?,
    val gatewayKind: String,   // "exclusive" | "inclusive" | "parallel" | "eventBased" | "complex"
    val branches: List<Branch>,
) : Step {
    override fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "kind" to "gateway",
        "id" to id,
        "name" to name,
        "gatewayKind" to gatewayKind,
        "branches" to branches.map { it.toMap() },
    )
}

data class Branch(
    val condition: String?,          // null iff isDefault (Flowable rule — the default flow has no condition)
    val isDefault: Boolean,
    val steps: List<Step>,
) {
    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "condition" to condition,
        "isDefault" to isDefault,
        "steps" to steps.map { it.toMap() },
    )
}

/** Call activity, signal throw, or message throw — one-level inlined (Q2). */
data class CallStep(
    override val id: String,
    override val name: String?,
    val callType: String,            // "callActivity" | "signalThrow" | "messageThrow"
    val targetKey: String?,
    val targetName: String? = null,
    val targetKind: String = "process",   // "process" | "case" | "decision" | "external" | "unresolved"
    val inline: Inline,
    // CMMN plan-item metadata — only populated when this call is a CMMN processTask/caseTask/decisionTask
    val entryCriteria: List<String>? = null,
    val exitCriteria: List<String>? = null,
    val manualActivation: Boolean = false,
) : Step {
    override fun toMap(): LinkedHashMap<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "kind" to "call",
            "id" to id,
            "name" to name,
            "callType" to callType,
            "targetKey" to targetKey,
        )
        if (targetName != null) m["targetName"] = targetName
        m["targetKind"] = targetKind
        m["inline"] = inline.toMap()
        if (!entryCriteria.isNullOrEmpty()) m["entryCriteria"] = entryCriteria
        if (!exitCriteria.isNullOrEmpty()) m["exitCriteria"] = exitCriteria
        if (manualActivation) m["manualActivation"] = true
        return m
    }
}

sealed interface Inline {
    fun toMap(): LinkedHashMap<String, Any?>
}

data class InlineResolved(val steps: List<Step>) : Inline {
    override fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "resolved" to true,
        "steps" to steps.map { it.toMap() },
    )
}

data class InlineUnresolved(
    val reason: String,               // "deeper-than-one-level" | "unresolvable" | "external"
    val stepCount: Int? = null,
) : Inline {
    override fun toMap(): LinkedHashMap<String, Any?> {
        val m = linkedMapOf<String, Any?>("resolved" to false, "reason" to reason)
        if (stepCount != null) m["stepCount"] = stepCount
        return m
    }
}

/**
 * CMMN stage — a container for plan items, potentially nested. Used only inside [CaseBody]; BPMN
 * sub-processes reuse [SubProcessStep] instead. Entry/exit criteria (sentries) are deferred to a
 * later phase per the schema doc; only structure is captured in v1.
 */
data class StageStep(
    override val id: String,
    override val name: String?,
    val autoComplete: Boolean = false,
    val steps: List<Step> = emptyList(),
    val entryCriteria: List<String>? = null,
    val exitCriteria: List<String>? = null,
    val manualActivation: Boolean = false,
) : Step {
    override fun toMap(): LinkedHashMap<String, Any?> {
        val m = linkedMapOf<String, Any?>("kind" to "stage", "id" to id, "name" to name)
        if (autoComplete) m["autoComplete"] = true
        if (!entryCriteria.isNullOrEmpty()) m["entryCriteria"] = entryCriteria
        if (!exitCriteria.isNullOrEmpty()) m["exitCriteria"] = exitCriteria
        if (manualActivation) m["manualActivation"] = true
        m["steps"] = steps.map { it.toMap() }
        return m
    }
}

/** Intermediate catch/throw event that isn't attached as a boundary event. */
data class EventStep(
    override val id: String,
    override val name: String?,
    val eventKind: String,           // "intermediateCatch" | "intermediateThrow"
    val eventType: String,           // "message" | "signal" | "timer" | "error" | ...
    val spec: String? = null,
) : Step {
    override fun toMap(): LinkedHashMap<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "kind" to "event",
            "id" to id,
            "name" to name,
            "eventKind" to eventKind,
            "eventType" to eventType,
        )
        if (spec != null) m["spec"] = spec
        return m
    }
}
