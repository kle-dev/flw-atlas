package com.flowable.atlas.preview

import com.flowable.atlas.diagram.Picture
import java.awt.geom.Point2D

/**
 * Which model element lies under a point of a drawn picture — a diagram shape, a form component, a
 * decision rule. The places come with the picture ([Picture.hotspots], in viewBox coordinates); the
 * viewBox says where the drawing's origin sits. The innermost element wins, so a task inside a lane is
 * the task and a field inside a panel the field.
 */
internal class HitMap private constructor(private val picture: Picture) {

    /** The element id under [p], a point in the SVG document's coordinates, or null. */
    fun elementAt(p: Point2D.Double): String? = picture.hotspotAt(picture.viewBox.x + p.x, picture.viewBox.y + p.y)?.id

    companion object {
        /** The map of [picture], or null when it has no elements to hit (a bundled export SVG has none). */
        fun of(picture: Picture?): HitMap? = picture?.takeIf { it.hotspots.isNotEmpty() }?.let(::HitMap)
    }
}
