package com.flowable.atlas.preview

import com.github.weisj.jsvg.SVGDocument
import com.github.weisj.jsvg.view.ViewBox
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Point2D
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Paints an SVG document with plain Swing, through JSVG, the SVG library the platform itself draws its
 * icons with. The IDE's own SVG viewer is a JCEF browser; under Remote Development every resource it
 * loads is one more round trip to the client. A painted component reaches the client as pixels like
 * any other Swing component.
 *
 * Fits the width of the view by default (never enlarging past 100 %). [zoomBy] switches to a fixed
 * zoom, and [fitWidth] switches back. Ctrl/⌘ + wheel zooms about the pointer, a drag pans, a plain wheel
 * scrolls; a click that is not a drag reports the point in the drawing's own coordinates to [onClick].
 */
internal class SvgCanvas : JComponent(), Scrollable {

    var document: SVGDocument? = null
        set(value) {
            field = value
            revalidate()
            repaint()
        }

    /** Told where a click (not a drag) landed, in the document's coordinates. */
    var onClick: ((Point2D.Double) -> Unit)? = null

    private var fixedZoom: Double? = null

    init {
        val mouse = object : MouseAdapter() {
            private var pressedAt: Point? = null
            private var dragged = false

            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                pressedAt = e.locationOnScreen
                dragged = false
            }

            override fun mouseDragged(e: MouseEvent) {
                val from = pressedAt ?: return
                val viewport = parent as? JViewport ?: return
                val now = e.locationOnScreen
                val dx = from.x - now.x
                val dy = from.y - now.y
                if (!dragged && Math.abs(dx) + Math.abs(dy) < DRAG_THRESHOLD) return
                dragged = true
                cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                val view = viewport.viewPosition
                val maxX = maxOf(0, width - viewport.width)
                val maxY = maxOf(0, height - viewport.height)
                viewport.viewPosition = Point((view.x + dx).coerceIn(0, maxX), (view.y + dy).coerceIn(0, maxY))
                pressedAt = now
            }

            override fun mouseReleased(e: MouseEvent) {
                if (pressedAt != null && !dragged) toDocument(e.point)?.let { onClick?.invoke(it) }
                pressedAt = null
                dragged = false
                cursor = Cursor.getDefaultCursor()
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                if (e.isControlDown || e.isMetaDown) {
                    zoomBy(if (e.preciseWheelRotation < 0) 1.1 else 1 / 1.1, e.point)
                    e.consume()
                } else {
                    // A listener here takes the wheel from the scroll pane: hand a plain scroll back to it.
                    parent?.dispatchEvent(SwingUtilities.convertMouseEvent(this@SvgCanvas, e, parent))
                }
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
    }

    fun zoomBy(factor: Double) = zoomBy(factor, null)

    /** Zoom by [factor], keeping the drawing under [anchor] (a point of this component) where it is. */
    private fun zoomBy(factor: Double, anchor: Point?) {
        val before = zoom()
        val docPoint = anchor?.let { toDocument(it) }
        fixedZoom = (before * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        revalidate()
        repaint()
        val viewport = parent as? JViewport ?: return
        if (anchor == null || docPoint == null) return
        // Once the new size is laid out, scroll so the same drawing point sits under the pointer again.
        SwingUtilities.invokeLater {
            val z = zoom()
            val now = Point((originX(z) + docPoint.x * z).toInt(), (MARGIN + docPoint.y * z).toInt())
            val inView = Point(anchor.x - viewport.viewPosition.x, anchor.y - viewport.viewPosition.y)
            viewport.viewPosition = Point(maxOf(0, now.x - inView.x), maxOf(0, now.y - inView.y))
        }
    }

    fun fitWidth() {
        fixedZoom = null
        revalidate()
        repaint()
    }

    /** [p], a point of this component, in the document's coordinates — null off the drawing. */
    internal fun toDocument(p: Point): Point2D.Double? {
        val doc = document ?: return null
        val z = zoom()
        val size = doc.size()
        val x = (p.x - originX(z)) / z
        val y = (p.y - MARGIN) / z
        if (x < 0 || y < 0 || x > size.width || y > size.height) return null
        return Point2D.Double(x, y)
    }

    private fun originX(z: Double): Double {
        val w = document?.size()?.width ?: 0f
        // Centred when the drawing is narrower than the view.
        return maxOf(MARGIN.toDouble(), (width - w * z) / 2)
    }

    private fun zoom(): Double {
        fixedZoom?.let { return it }
        val doc = document ?: return 1.0
        val available = (parent?.width ?: width).toDouble()
        val w = doc.size().width.toDouble()
        return if (w <= 0 || available <= 0) 1.0 else minOf(1.0, (available - 2 * MARGIN) / w).coerceAtLeast(MIN_ZOOM)
    }

    override fun getPreferredSize(): Dimension {
        val doc = document ?: return Dimension(0, 0)
        val z = zoom()
        val size = doc.size()
        return Dimension((size.width * z).toInt() + 2 * MARGIN, (size.height * z).toInt() + 2 * MARGIN)
    }

    override fun paintComponent(g: Graphics) {
        val doc = document ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val z = zoom()
            val size = doc.size()
            g2.translate(originX(z), MARGIN.toDouble())
            g2.scale(z, z)
            doc.render(this, g2, ViewBox(0f, 0f, size.width, size.height))
        } finally {
            g2.dispose()
        }
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 16

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height - 16 else visibleRect.width - 16

    /** Fitting the width means following the view's width; a fixed zoom scrolls sideways instead. */
    override fun getScrollableTracksViewportWidth(): Boolean = fixedZoom == null

    override fun getScrollableTracksViewportHeight(): Boolean = false

    private companion object {
        const val MARGIN = 12
        const val MIN_ZOOM = 0.1
        const val MAX_ZOOM = 4.0
        const val DRAG_THRESHOLD = 4
    }
}
