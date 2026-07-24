package com.flowable.atlas.diagram

/**
 * A format-agnostic, notation-agnostic snapshot of a model's *diagram interchange* (DI) — the shapes
 * and connectors with their absolute canvas coordinates — ready to be painted to SVG by
 * [DiagramSvgRenderer].
 *
 * Both DI sources feed this same shape: [XmlDiExtractor] reads the `bpmndi`/`cmmndi`/`dmndi` tags of a
 * deployment-XML model, [OryxJsonDiExtractor] reads the ORYX `childShapes` tree of a Flowable Design
 * workspace JSON. The painter then only ever sees geometry, never a parser.
 *
 * Coordinates are absolute (top-left origin, y grows downward) and already de-nested — the JSON
 * extractor folds ORYX's parent-relative bounds into absolute values before building these.
 */
data class DiagramGeometry(
    val shapes: List<DiaShape>,
    val edges: List<DiaEdge>,
    val notation: Notation,
) {
    /** True when there is nothing to draw — the caller treats this as "no diagram" (null SVG). */
    fun isEmpty(): Boolean = shapes.isEmpty() && edges.isEmpty()

    enum class Notation { BPMN, CMMN, DMN }
}

/** A positioned node (task, event, gateway, …). `x`/`y` is the top-left corner. */
data class DiaShape(
    val elementId: String,
    val kind: ShapeKind,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val label: String?,
) {
    val centerX: Double get() = x + width / 2
    val centerY: Double get() = y + height / 2
}

/** A connector (sequence flow, association, information requirement, …) as an ordered waypoint path. */
data class DiaEdge(
    val elementId: String,
    val kind: EdgeKind,
    val waypoints: List<Point>,
    val label: String?,
)

data class Point(val x: Double, val y: Double)

/**
 * The silhouette a shape is drawn with. Notation-specific tags/stencils are mapped onto this small
 * vocabulary by the extractors ([bpmnKind]/[cmmnKind]/[dmnKind] in [DiagramKinds]); anything unknown
 * falls back to [GENERIC_BOX] (a rounded rectangle) so an unfamiliar element still renders in place.
 */
enum class ShapeKind {
    TASK, SUBPROCESS,
    EVENT_START, EVENT_END, EVENT_INTERMEDIATE,
    GATEWAY_EXCLUSIVE, GATEWAY_PARALLEL, GATEWAY_INCLUSIVE, GATEWAY_EVENT,
    POOL, LANE, DATA_OBJECT, TEXT_ANNOTATION,
    CMMN_STAGE, CMMN_TASK, CMMN_MILESTONE, CMMN_EVENT_LISTENER,
    DMN_DECISION, DMN_INPUT_DATA, DMN_BKM, DMN_KNOWLEDGE_SOURCE,
    GENERIC_BOX;

    val isEvent: Boolean get() = this == EVENT_START || this == EVENT_END || this == EVENT_INTERMEDIATE
    val isGateway: Boolean
        get() = this == GATEWAY_EXCLUSIVE || this == GATEWAY_PARALLEL ||
            this == GATEWAY_INCLUSIVE || this == GATEWAY_EVENT
}

enum class EdgeKind { SEQUENCE_FLOW, MESSAGE_FLOW, ASSOCIATION, CMMN_ASSOCIATION, DMN_REQUIREMENT }
