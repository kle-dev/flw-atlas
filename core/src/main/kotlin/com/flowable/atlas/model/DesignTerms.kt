package com.flowable.atlas.model

/**
 * The Flowable Design word for an Atlas identifier.
 *
 * Atlas's internal names come straight from the models it parses — `userTask`, `serviceTask/dmn`,
 * `planItemLifecycleListener`. They are precise, and they are what the graph stores, but a *reader*
 * should see the word Design shows: what you read in Atlas is then what you search for in Design.
 *
 * The frontend keeps the richer copy of this table (`DESIGN_TERMS` in `frontend/explorer.js` — label
 * plus an explaining sentence for the tooltip); this is the label-only half that the Markdown renderers
 * use. `DesignTermsSyncTest` pins the two together: every entry here must exist there with the same
 * label, and every vocabulary value Atlas emits must have a term at all.
 */
object DesignTerms {

    /**
     * `"<ns>:<key>"` → the Design label. Namespaces mirror the frontend table; only `el:` (model
     * elements) is needed here — the other namespaces are used by the explorer alone.
     */
    private val TERMS: Map<String, String> = mapOf(
        // --- BPMN activities ---
        "el:userTask" to "User task",
        "el:serviceTask" to "Service task",
        "el:serviceTask/dmn" to "Decision task",
        "el:scriptTask" to "Script task",
        "el:callActivity" to "Call activity",
        "el:subProcess" to "Sub-process",
        "el:transaction" to "Transaction",
        "el:adhocSubProcess" to "Ad-hoc sub-process",
        "el:sendTask" to "Send task",
        "el:receiveTask" to "Receive task",
        "el:manualTask" to "Manual task",
        "el:task" to "Task",
        // --- BPMN events + gateways ---
        "el:startEvent" to "Start event",
        "el:endEvent" to "End event",
        "el:boundaryEvent" to "Boundary event",
        "el:intermediateCatchEvent" to "Intermediate catch event",
        "el:intermediateThrowEvent" to "Intermediate throw event",
        "el:exclusiveGateway" to "Exclusive gateway",
        "el:parallelGateway" to "Parallel gateway",
        "el:inclusiveGateway" to "Inclusive gateway",
        "el:eventBasedGateway" to "Event gateway",
        "el:complexGateway" to "Complex gateway",
        "el:sequenceFlow" to "Sequence flow",
        // --- CMMN plan items ---
        "el:casePlanModel" to "Case plan model",
        "el:stage" to "Stage",
        "el:planFragment" to "Plan fragment",
        "el:humanTask" to "Human task",
        "el:humanTaskWithService" to "Human task with service",
        "el:processTask" to "Process task",
        "el:caseTask" to "Case task",
        "el:decisionTask" to "Decision task",
        "el:milestone" to "Milestone",
        "el:entryCriterion" to "Entry criterion",
        "el:exitCriterion" to "Exit criterion",
        "el:timerEventListener" to "Timer",
        "el:userEventListener" to "User event listener",
        "el:signalEventListener" to "Signal listener",
        "el:variableEventListener" to "Variable listener",
        "el:eventListener" to "Event listener",
        // --- listeners ---
        "el:executionListener" to "Execution listener",
        "el:taskListener" to "Task listener",
        "el:planItemLifecycleListener" to "Lifecycle listener",
        // --- model types, singular and lowercase: they are counted mid-sentence ("2 data objects").
        // The explorer's plural sidebar labels live in `TM`; the sync test keeps both in step.
        "type:process" to "process",
        "type:case" to "case",
        "type:decision" to "decision table",
        "type:form" to "form",
        "type:page" to "page",
        "type:dataObject" to "data object",
        "type:dataDictionary" to "data dictionary",
        "type:service" to "service",
        "type:agent" to "AI agent",
        "type:channel" to "channel",
        "type:event" to "event",
        "type:action" to "action",
        "type:query" to "query",
        "type:template" to "template",
        "type:sequence" to "sequence",
        "type:securityPolicy" to "security policy",
        "type:variableExtractor" to "variable extractor",
        "type:liquibase" to "Liquibase changelog",
    )

    /** The Design label for [key] in [ns] — or [key] itself when Atlas has no term for it. */
    fun label(ns: String, key: Any?): String {
        val k = key?.toString()?.trim().orEmpty()
        if (k.isEmpty()) return ""
        return TERMS["$ns:$k"] ?: k
    }

    /** `"2 data objects"` / `"1 decision table"` — the Design word for a model type, counted. */
    fun counted(n: Int, type: String): String {
        val one = label("type", type)
        return "$n " + if (n == 1) one else pluralize(one)
    }

    /** English plural of a Design term. Enough for the vocabulary at hand (`query` → `queries`). */
    fun pluralize(term: String): String = when {
        term.endsWith("y") && !term.endsWith("ay") -> term.dropLast(1) + "ies"
        term.endsWith("s") || term.endsWith("x") || term.endsWith("ch") || term.endsWith("sh") -> term + "es"
        else -> term + "s"
    }

    /** Every `"<ns>:<key>"` this table defines — read by the sync test. */
    val keys: Set<String> get() = TERMS.keys
}
