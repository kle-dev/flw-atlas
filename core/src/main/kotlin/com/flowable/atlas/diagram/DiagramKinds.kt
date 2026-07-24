package com.flowable.atlas.diagram

/**
 * Maps a notation element — identified either by its XML local tag (`userTask`, `exclusiveGateway`, …)
 * or by its ORYX stencil id (`UserTask`, `ExclusiveGateway`, …) — onto the small [ShapeKind]/[EdgeKind]
 * vocabulary the painter understands. Matching is case-insensitive and pattern-based so the two
 * spellings (XML lower-camel tag vs. ORYX upper-camel stencil) collapse to one rule set, and any
 * unrecognised element still resolves to a sensible default rather than vanishing.
 */
object DiagramKinds {

    /** Edge-typed names (both XML tags and ORYX stencils) → [EdgeKind], or null if [name] is a shape. */
    fun edgeKind(name: String, notation: DiagramGeometry.Notation): EdgeKind? {
        val n = name.lowercase()
        return when {
            n.contains("sequenceflow") -> EdgeKind.SEQUENCE_FLOW
            n.contains("messageflow") -> EdgeKind.MESSAGE_FLOW
            n.contains("requirement") -> EdgeKind.DMN_REQUIREMENT   // information/knowledge/authorityRequirement
            n.contains("association") ->
                if (notation == DiagramGeometry.Notation.CMMN) EdgeKind.CMMN_ASSOCIATION else EdgeKind.ASSOCIATION
            n.contains("planitemonpart") || n == "connector" -> EdgeKind.CMMN_ASSOCIATION
            else -> null
        }
    }

    /** Shape-typed names → [ShapeKind]. Never null: unknown elements become [ShapeKind.GENERIC_BOX]. */
    fun shapeKind(name: String, notation: DiagramGeometry.Notation): ShapeKind = when (notation) {
        DiagramGeometry.Notation.BPMN -> bpmnKind(name)
        DiagramGeometry.Notation.CMMN -> cmmnKind(name)
        DiagramGeometry.Notation.DMN -> dmnKind(name)
    }

    private fun bpmnKind(name: String): ShapeKind {
        val n = name.lowercase()
        return when {
            n.endsWith("gateway") -> when {
                n.contains("parallel") -> ShapeKind.GATEWAY_PARALLEL
                n.contains("inclusive") -> ShapeKind.GATEWAY_INCLUSIVE
                n.contains("event") || n.contains("complex") -> ShapeKind.GATEWAY_EVENT
                else -> ShapeKind.GATEWAY_EXCLUSIVE   // exclusive + any other gateway
            }
            n.startsWith("start") -> ShapeKind.EVENT_START
            n.startsWith("end") -> ShapeKind.EVENT_END
            n.contains("event") -> ShapeKind.EVENT_INTERMEDIATE  // intermediate/boundary/catch/throw
            n == "callactivity" -> ShapeKind.SUBPROCESS
            n.contains("subprocess") || n == "transaction" || n.contains("adhoc") -> ShapeKind.SUBPROCESS
            n.endsWith("task") || n == "task" -> ShapeKind.TASK
            n.contains("participant") || n == "pool" -> ShapeKind.POOL
            n == "lane" -> ShapeKind.LANE
            n.contains("dataobject") || n.contains("datastore") -> ShapeKind.DATA_OBJECT
            n.contains("textannotation") -> ShapeKind.TEXT_ANNOTATION
            else -> ShapeKind.GENERIC_BOX
        }
    }

    private fun cmmnKind(name: String): ShapeKind {
        val n = name.lowercase()
        return when {
            n.contains("caseplanmodel") || n == "stage" || n.contains("planningtable") -> ShapeKind.CMMN_STAGE
            n.contains("milestone") -> ShapeKind.CMMN_MILESTONE
            n.contains("listener") || n.contains("eventlistener") || n.contains("timerevent") ->
                ShapeKind.CMMN_EVENT_LISTENER
            n.contains("task") -> ShapeKind.CMMN_TASK   // human/process/case/decision/task
            n.contains("stage") -> ShapeKind.CMMN_STAGE
            else -> ShapeKind.GENERIC_BOX
        }
    }

    private fun dmnKind(name: String): ShapeKind {
        val n = name.lowercase()
        return when {
            n.contains("businessknowledge") || n == "bkm" -> ShapeKind.DMN_BKM
            n.contains("knowledgesource") -> ShapeKind.DMN_KNOWLEDGE_SOURCE
            n.contains("inputdata") -> ShapeKind.DMN_INPUT_DATA
            n.contains("decision") -> ShapeKind.DMN_DECISION
            else -> ShapeKind.GENERIC_BOX
        }
    }
}
