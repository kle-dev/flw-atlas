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

/**
 * A positioned node (task, event, gateway, …). `x`/`y` is the top-left corner.
 *
 * [kind] is the silhouette; [icon] is the *type* glyph painted inside it and [typeLabel] the name
 * Flowable Design gives that type — together they are what turns a wall of identical rectangles into a
 * diagram you can read. Both are optional: an unrecognised element still renders as a plain box.
 */
data class DiaShape(
    val elementId: String,
    val kind: ShapeKind,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val label: String?,
    val icon: DiaIcon? = null,
    val typeLabel: String? = null,
    val markers: Set<DiaMarker> = emptySet(),
) {
    val centerX: Double get() = x + width / 2
    val centerY: Double get() = y + height / 2
}

/**
 * The type glyph painted inside a shape. Notation-neutral on purpose — a BPMN user task and a CMMN human
 * task get the same person icon, exactly as they do in Flowable Design.
 *
 * [slug] is emitted as `data-icon` on the shape's SVG group, which makes the output greppable (and tests
 * readable) without parsing path data. Resolution from a model element lives in [DiagramIcons].
 */
enum class DiaIcon(val slug: String) {
    USER("user"), SERVICE("service"), SCRIPT("script"), MANUAL("manual"),
    BUSINESS_RULE("business-rule"), DECISION("decision"), SEND("send"), RECEIVE("receive"),
    MAIL("mail"), HTTP("http"), SERVICE_REGISTRY("service-registry"), AGENT("agent"),
    DATA_OBJECT("data-object"), DOCUMENT("document"), SEQUENCE("sequence"),
    INIT_VARIABLES("init-variables"), AUDIT("audit"), EXTERNAL_WORKER("external-worker"),
    SHELL("shell"), CAMEL("camel"), CASE("case"), PROCESS("process"),
    SEND_EVENT("send-event"), RECEIVE_EVENT("receive-event"),
    TIMER("timer"), MESSAGE("message"), SIGNAL("signal"), ERROR("error"),
    ESCALATION("escalation"), TERMINATE("terminate"), CONDITIONAL("conditional"),
    COMPENSATION("compensation"), LINK("link"),
}

/** Decorations BPMN draws *in addition* to a shape's silhouette and type glyph. */
enum class DiaMarker {
    /** Multi-instance, parallel (‖ at the bottom edge) / sequential (≡). */
    MI_PARALLEL, MI_SEQUENTIAL,

    /** Standard/loop marker (↻). */
    LOOP,

    /** A sub-process drawn collapsed — `isExpanded="false"` in BPMN DI, Design's collapsed stencil in a
     *  workspace model — which is the one case that earns the `[+]` box at the bottom edge. */
    COLLAPSED,

    /** A boundary event with `cancelActivity="false"`, or an event sub-process start that does not
     *  interrupt — drawn with a dashed circle rather than a solid one. */
    NON_INTERRUPTING,
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
    TASK, SUBPROCESS, CALL_ACTIVITY, EVENT_SUBPROCESS,
    EVENT_START, EVENT_END, EVENT_INTERMEDIATE,
    GATEWAY_EXCLUSIVE, GATEWAY_PARALLEL, GATEWAY_INCLUSIVE, GATEWAY_EVENT,
    POOL, LANE, DATA_OBJECT, TEXT_ANNOTATION,
    CMMN_STAGE, CMMN_TASK, CMMN_MILESTONE, CMMN_EVENT_LISTENER,

    /** Sentry diamonds on a plan item's border — entry hollow, exit filled, as CMMN draws them. */
    CMMN_CRITERION_ENTRY, CMMN_CRITERION_EXIT,
    DMN_DECISION, DMN_INPUT_DATA, DMN_BKM, DMN_KNOWLEDGE_SOURCE,
    GENERIC_BOX;

    val isEvent: Boolean get() = this == EVENT_START || this == EVENT_END || this == EVENT_INTERMEDIATE
    val isGateway: Boolean
        get() = this == GATEWAY_EXCLUSIVE || this == GATEWAY_PARALLEL ||
            this == GATEWAY_INCLUSIVE || this == GATEWAY_EVENT
}

enum class EdgeKind { SEQUENCE_FLOW, MESSAGE_FLOW, ASSOCIATION, CMMN_ASSOCIATION, DMN_REQUIREMENT }
