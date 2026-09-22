package com.flowable.atlas.preview

import com.github.weisj.jsvg.SVGDocument
import com.github.weisj.jsvg.view.ViewBox
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * Paints an SVG document with plain Swing, through JSVG, the SVG library the platform itself draws its
 * icons with. The IDE's own SVG viewer is a JCEF browser; under Remote Development every resource it
 * loads is one more round trip to the client. A painted component reaches the client as pixels like
 * any other Swing component.
 *
 * Fits the width of the view by default (never enlarging past 100 %). [zoomBy] switches to a fixed
 * zoom, and [fitWidth] switches back.
 */
internal class SvgCanvas : JComponent(), Scrollable {

    var document: SVGDocument? = null
        set(value) {
            field = value
            revalidate()
            repaint()
        }

    private var fixedZoom: Double? = null

    fun zoomBy(factor: Double) {
        fixedZoom = (zoom() * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        revalidate()
        repaint()
    }

    fun fitWidth() {
        fixedZoom = null
        revalidate()
        repaint()
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
            // Centred when the drawing is narrower than the view.
            val x = maxOf(MARGIN.toDouble(), (width - size.width * z) / 2)
            g2.translate(x, MARGIN.toDouble())
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
    }
}
