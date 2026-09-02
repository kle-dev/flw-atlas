package com.flowable.atlas.diagram

import com.flowable.atlas.parsing.AtlasXml

/**
 * Extracts [DiagramGeometry] from a deployment-XML model's diagram-interchange tags via the shared,
 * namespace-agnostic [AtlasXml] DOM (so `bpmndi:BPMNShape`, `omgdc:Bounds`, `di:waypoint` … are seen
 * by their local names alone).
 *
 * Each `*Shape`/`*Edge` references a model element by id (`bpmnElement` / `cmmnElementRef` /
 * `dmnElementRef`); we resolve that id back to the element's local tag to pick a [ShapeKind]. BPMN
 * waypoints are absolute canvas coordinates, so edges are drawn straight from their `<waypoint>` list.
 * Returns an empty geometry when the model carries no DI (e.g. a decision table with no DRD).
 */
object XmlDiExtractor {

    fun extract(bytes: ByteArray, notation: DiagramGeometry.Notation): DiagramGeometry {
        val root = try {
            AtlasXml.parse(bytes)
        } catch (e: Exception) {
            return DiagramGeometry(emptyList(), emptyList(), notation)
        }
        val names = DiNames.of(notation)

        // 1. Index every element with an id → its tag + name. CMMN shapes point at a planItem, which
        //    only carries a `definitionRef`, so remember that hop to reach the real element's tag/name.
        val tagById = HashMap<String, String>()
        val nameById = HashMap<String, String?>()
        val planItemDefRef = HashMap<String, String>()
        // What the element *is*, beyond its tag: the Design stencil it was modelled from, the
        // `flowable:type` discriminator that turns a bare <serviceTask> into an HTTP/agent/registry call,
        // its event definition, and the markers BPMN paints on top (see DiagramIcons / DiaMarker).
        val elementById = HashMap<String, ElementFacts>()
        walk(root) { el ->
            el.attr("id")?.let { id ->
                tagById[id] = el.tag
                nameById[id] = el.attr("name")
                if (el.tag == "planItem") el.attr("definitionRef")?.let { planItemDefRef[id] = it }
                elementById[id] = facts(el)
            }
        }

        fun resolvedTag(ref: String): String? =
            planItemDefRef[ref]?.let { tagById[it] } ?: tagById[ref]

        fun resolvedName(ref: String): String? =
            nameById[ref] ?: planItemDefRef[ref]?.let { nameById[it] }

        // A CMMN shape points at a planItem; the type lives on the definition it references.
        fun resolvedFacts(ref: String): ElementFacts =
            planItemDefRef[ref]?.let { elementById[it] } ?: elementById[ref] ?: ElementFacts()

        // 2. Shapes: the shape's *direct-child* Bounds is the geometry (a nested label Bounds is not).
        val shapes = ArrayList<DiaShape>()
        for (shape in root.iter(names.shape)) {
            val ref = shape.attr(names.elementRef) ?: continue
            val bounds = shape.findChild(names.bounds) ?: continue
            val x = bounds.attr("x")?.toDoubleOrNull() ?: continue
            val y = bounds.attr("y")?.toDoubleOrNull() ?: continue
            val w = bounds.attr("width")?.toDoubleOrNull() ?: continue
            val h = bounds.attr("height")?.toDoubleOrNull() ?: continue
            val tag = resolvedTag(ref) ?: ""
            val f = resolvedFacts(ref)
            val resolved = DiagramIcons.resolve(f.stencil, f.flowableType, tag, f.eventDef)
            val kind = DiagramKinds.shapeKind(tag, notation, f.stencil)
            // BPMN DI says whether a sub-process is drawn expanded (its children laid out inside it) or
            // collapsed (`isExpanded="false"`: a box with a [+]); the marker used to be painted on every
            // sub-process, so an expanded one said "collapsed" over its own children.
            val collapsed = kind == ShapeKind.SUBPROCESS &&
                shape.attr("isExpanded")?.equals("false", ignoreCase = true) == true
            val markers = if (collapsed) f.markers + DiaMarker.COLLAPSED else f.markers
            // CMMN DI references the planItem, but everything parsed from the model (plan tree,
            // parameters, script bodies) is keyed by the *definition* — emit the definition's id so
            // the explorer joins diagram ↔ details by id, not by a name heuristic.
            val elementId = planItemDefRef[ref] ?: ref
            shapes.add(
                DiaShape(elementId, kind, x, y, w, h, resolvedName(ref), resolved.icon, resolved.typeLabel, markers),
            )
        }

        // 3. Edges: straight polyline through the absolute waypoints.
        val edges = ArrayList<DiaEdge>()
        for (edge in root.iter(names.edge)) {
            val wps = edge.findChildren(names.waypoint).mapNotNull { wp ->
                val x = wp.attr("x")?.toDoubleOrNull() ?: return@mapNotNull null
                val y = wp.attr("y")?.toDoubleOrNull() ?: return@mapNotNull null
                Point(x, y)
            }
            if (wps.size < 2) continue
            val ref = edge.attr(names.elementRef)
            val tag = ref?.let { tagById[it] } ?: ""
            val kind = DiagramKinds.edgeKind(tag, notation) ?: defaultEdgeKind(notation)
            edges.add(DiaEdge(ref ?: "", kind, wps, ref?.let { nameById[it] }))
        }

        return DiagramGeometry(shapes, edges, notation)
    }

    private fun defaultEdgeKind(notation: DiagramGeometry.Notation): EdgeKind = when (notation) {
        DiagramGeometry.Notation.DMN -> EdgeKind.DMN_REQUIREMENT
        DiagramGeometry.Notation.CMMN -> EdgeKind.CMMN_ASSOCIATION
        DiagramGeometry.Notation.BPMN -> EdgeKind.SEQUENCE_FLOW
    }

    /** Everything about an element that decides its glyph and decorations, read once per element. */
    private data class ElementFacts(
        val stencil: String? = null,
        val flowableType: String? = null,
        val eventDef: String? = null,
        val markers: Set<DiaMarker> = emptySet(),
    )

    private val EVENT_DEF_SUFFIX = "EventDefinition"

    private fun facts(el: AtlasXml.El): ElementFacts {
        val ext = el.findChild("extensionElements")
        val markers = LinkedHashSet<DiaMarker>()
        el.findChild("multiInstanceLoopCharacteristics")?.let { mi ->
            markers += if (mi.attr("isSequential") == "true") DiaMarker.MI_SEQUENTIAL else DiaMarker.MI_PARALLEL
        }
        if (el.findChild("standardLoopCharacteristics") != null) markers += DiaMarker.LOOP
        // BPMN draws a non-interrupting boundary/event-subprocess start with a dashed outline
        if (el.attr("cancelActivity") == "false") markers += DiaMarker.NON_INTERRUPTING
        return ElementFacts(
            stencil = ext?.childText("stencilid"),
            flowableType = el.attr("type"),
            eventDef = el.children.firstOrNull { it.tag.endsWith(EVENT_DEF_SUFFIX) }?.tag,
            markers = markers,
        )
    }

    /** Pre-order walk of the whole element tree (AtlasXml exposes only per-tag iteration). */
    private fun walk(el: AtlasXml.El, visit: (AtlasXml.El) -> Unit) {
        visit(el)
        for (c in el.children) walk(c, visit)
    }

    /** The local tag names the three OMG DI dialects use for shapes, edges, bounds and waypoints. */
    private data class DiNames(
        val shape: String,
        val edge: String,
        val elementRef: String,
        val bounds: String,
        val waypoint: String,
    ) {
        companion object {
            fun of(notation: DiagramGeometry.Notation): DiNames = when (notation) {
                DiagramGeometry.Notation.BPMN ->
                    DiNames("BPMNShape", "BPMNEdge", "bpmnElement", "Bounds", "waypoint")
                DiagramGeometry.Notation.CMMN ->
                    DiNames("CMMNShape", "CMMNEdge", "cmmnElementRef", "Bounds", "waypoint")
                DiagramGeometry.Notation.DMN ->
                    DiNames("DMNShape", "DMNEdge", "dmnElementRef", "Bounds", "waypoint")
            }
        }
    }
}
