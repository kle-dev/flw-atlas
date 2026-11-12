package com.flowable.atlas.preview

import com.flowable.atlas.diagram.Picture
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D

/**
 * Which model element lies under a point of a drawn picture — a diagram shape, a form component, a
 * decision rule. The places come with the picture ([Picture.hotspots], in viewBox coordinates); the
 * viewBox says where the drawing's origin sits. The innermost element wins, so a task inside a lane is
 * the task and a field inside a panel the field.
 */
internal class HitMap private constructor(private val picture: Picture) {

    /** The element id under [p], a point in the SVG document's coordinates, or null. */
    fun elementAt(p: Point2D.Double): String? = hotspotAt(p)?.id

    /** The model the element under [p] opens — `<type>:<key>`, a form's subform — or null. */
    fun refAt(p: Point2D.Double): String? = hotspotAt(p)?.ref

    /** The element under [p] with its box in the document's coordinates — what the preview outlines on hover. */
    fun hitAt(p: Point2D.Double): Hit? = hotspotAt(p)?.let {
        Hit(it.id, Rectangle2D.Double(it.x - picture.viewBox.x, it.y - picture.viewBox.y, it.width, it.height), it.ref)
    }

    data class Hit(val id: String, val bounds: Rectangle2D.Double, val ref: String?)

    /** Whether some element opens another model — the preview says a double click does that. */
    val opensModels: Boolean get() = picture.hotspots.any { it.ref != null }

    private fun hotspotAt(p: Point2D.Double) = picture.hotspotAt(picture.viewBox.x + p.x, picture.viewBox.y + p.y)

    companion object {
        /** The map of [picture], or null when it has no elements to hit (a bundled export SVG has none). */
        fun of(picture: Picture?): HitMap? = picture?.takeIf { it.hotspots.isNotEmpty() }?.let(::HitMap)
    }
}
