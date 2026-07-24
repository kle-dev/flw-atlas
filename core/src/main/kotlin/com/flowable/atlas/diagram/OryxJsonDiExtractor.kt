package com.flowable.atlas.diagram

import com.flowable.atlas.model.MiniJson

/**
 * Extracts [DiagramGeometry] from a Flowable Design workspace model JSON (the ORYX/stencilset shape
 * tree stored under `childShapes`). Each shape carries its own `stencil.id` (→ [ShapeKind]) and a
 * `bounds` given by `upperLeft`/`lowerRight`.
 *
 * Two ORYX quirks are handled here so the painter only ever sees absolute geometry:
 *  - **Nested bounds are parent-relative** — a shape inside a pool/subprocess is positioned relative
 *    to that container's upper-left, so we fold the accumulated parent offset into every shape.
 *  - **Edge waypoints (`dockers`) are notoriously relative/mixed** — rather than decode them, edges are
 *    drawn straight between the centres of their source and target nodes (source = the node whose
 *    `outgoing` lists the edge; target = the edge's `target`/`outgoing`), which is robust and readable.
 */
object OryxJsonDiExtractor {

    fun extract(bytes: ByteArray, notation: DiagramGeometry.Notation): DiagramGeometry {
        val root = MiniJson.parseOrNull(String(bytes, Charsets.UTF_8)) as? Map<*, *>
            ?: return DiagramGeometry(emptyList(), emptyList(), notation)

        val shapes = ArrayList<DiaShape>()
        val centers = HashMap<String, Point>()
        val edgeSource = HashMap<String, String>()          // edge resourceId → source node resourceId
        val rawEdges = ArrayList<RawEdge>()

        recurse(root, 0.0, 0.0, notation, shapes, centers, edgeSource, rawEdges)

        val edges = rawEdges.mapNotNull { re ->
            val from = edgeSource[re.id]?.let { centers[it] } ?: return@mapNotNull null
            val to = re.targetId?.let { centers[it] } ?: return@mapNotNull null
            DiaEdge(re.id, re.kind, listOf(from, to), null)
        }
        return DiagramGeometry(shapes, edges, notation)
    }

    private fun recurse(
        parent: Map<*, *>,
        offsetX: Double,
        offsetY: Double,
        notation: DiagramGeometry.Notation,
        shapes: MutableList<DiaShape>,
        centers: MutableMap<String, Point>,
        edgeSource: MutableMap<String, String>,
        rawEdges: MutableList<RawEdge>,
    ) {
        val children = parent["childShapes"] as? List<*> ?: return
        for (childAny in children) {
            val child = childAny as? Map<*, *> ?: continue
            val id = child["resourceId"] as? String ?: continue
            val stencil = (child["stencil"] as? Map<*, *>)?.get("id") as? String ?: ""

            val edgeKind = DiagramKinds.edgeKind(stencil, notation)
            if (edgeKind != null) {
                val target = (child["target"] as? Map<*, *>)?.get("resourceId") as? String
                    ?: firstOutgoing(child)
                rawEdges.add(RawEdge(id, edgeKind, target))
                continue    // edges carry no meaningful child geometry
            }

            val b = bounds(child) ?: continue
            val ax = offsetX + b.ulx
            val ay = offsetY + b.uly
            val w = b.lrx - b.ulx
            val h = b.lry - b.uly
            val name = ((child["properties"] as? Map<*, *>)?.get("name") as? String)?.takeIf { it.isNotBlank() }
            shapes.add(DiaShape(id, DiagramKinds.shapeKind(stencil, notation), ax, ay, w, h, name))
            centers[id] = Point(ax + w / 2, ay + h / 2)

            (child["outgoing"] as? List<*>)?.forEach { o ->
                ((o as? Map<*, *>)?.get("resourceId") as? String)?.let { edgeSource[it] = id }
            }
            recurse(child, ax, ay, notation, shapes, centers, edgeSource, rawEdges)
        }
    }

    private fun firstOutgoing(shape: Map<*, *>): String? =
        (shape["outgoing"] as? List<*>)?.firstNotNullOfOrNull { (it as? Map<*, *>)?.get("resourceId") as? String }

    private fun bounds(shape: Map<*, *>): Bounds? {
        val b = shape["bounds"] as? Map<*, *> ?: return null
        val ul = b["upperLeft"] as? Map<*, *> ?: return null
        val lr = b["lowerRight"] as? Map<*, *> ?: return null
        val ulx = num(ul, "x") ?: return null
        val uly = num(ul, "y") ?: return null
        val lrx = num(lr, "x") ?: return null
        val lry = num(lr, "y") ?: return null
        return Bounds(ulx, uly, lrx, lry)
    }

    private fun num(m: Map<*, *>, key: String): Double? = (m[key] as? Number)?.toDouble()

    private data class Bounds(val ulx: Double, val uly: Double, val lrx: Double, val lry: Double)
    private data class RawEdge(val id: String, val kind: EdgeKind, val targetId: String?)
}
