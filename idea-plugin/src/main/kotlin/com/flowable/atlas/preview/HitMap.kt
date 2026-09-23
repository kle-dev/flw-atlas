package com.flowable.atlas.preview

import com.flowable.atlas.diagram.DiagramRenderer
import com.flowable.atlas.model.ModelType
import java.awt.geom.Point2D

/**
 * Which model element lies under a point of a drawn diagram. The shapes come from the same layout the
 * picture was drawn from ([DiagramRenderer.geometry]); the SVG's viewBox says where the drawing's origin
 * sits in the layout's coordinates. The innermost shape wins, so a task inside a lane or a stage is the
 * task. Only processes, cases and decisions have a layout; a form wireframe or a decision table has none.
 */
internal class HitMap private constructor(
    private val originX: Double,
    private val originY: Double,
    private val shapes: List<Shape>,
) {
    private data class Shape(val id: String, val x: Double, val y: Double, val w: Double, val h: Double)

    /** The element id under [p], a point in the SVG document's coordinates, or null. */
    fun elementAt(p: Point2D.Double): String? {
        val x = originX + p.x
        val y = originY + p.y
        return shapes.filter { x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h }
            .minByOrNull { it.w * it.h }?.id
    }

    companion object {
        private val VIEWBOX_RE = Regex("""viewBox="\s*(-?[\d.]+)[\s,]+(-?[\d.]+)""")

        fun of(svg: String, modelBytes: ByteArray?, fileName: String, type: ModelType): HitMap? {
            if (modelBytes == null || type !in setOf(ModelType.PROCESS, ModelType.CASE, ModelType.DECISION)) return null
            val head = svg.substring(0, minOf(svg.length, 600))
            val m = VIEWBOX_RE.find(head) ?: return null
            val geometry = runCatching { DiagramRenderer.geometry(modelBytes, fileName, type) }.getOrNull() ?: return null
            val shapes = geometry.shapes.filter { it.elementId.isNotBlank() }
                .map { Shape(it.elementId, it.x, it.y, it.width, it.height) }
            if (shapes.isEmpty()) return null
            return HitMap(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), shapes)
        }
    }
}
