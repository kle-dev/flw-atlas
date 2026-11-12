package com.flowable.atlas.preview

import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import com.github.weisj.jsvg.view.ViewBox
import junit.framework.TestCase
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * The wireframe and decision-table renderers write the font, the 12px size and the text colour once, on a
 * group around the drawing, and leave the 1px stroke width to the SVG default — a third fewer bytes in
 * every explorer page that embeds them. JSVG must paint that exactly as it painted the attributes written
 * on every element, or the preview would change for the sake of a smaller page.
 */
class SvgInheritanceTest : TestCase() {

    private fun paint(svg: String): BufferedImage {
        val doc = SvgFonts.resolvable(svg).byteInputStream().use { SVGLoader().load(it, null, LoaderContext.createDefault()) }!!
        val img = BufferedImage(160, 60, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
        doc.render(null, g, ViewBox(0f, 0f, 160f, 60f))
        g.dispose()
        return img
    }

    private fun same(a: BufferedImage, b: BufferedImage): Boolean =
        (0 until a.height).all { y -> (0 until a.width).all { x -> a.getRGB(x, y) == b.getRGB(x, y) } }

    private val font = "'Segoe UI', 'Helvetica Neue', Arial, sans-serif"

    fun testInheritedTextAttributesPaintLikeExplicitOnes() {
        val explicit = """<svg xmlns="http://www.w3.org/2000/svg" width="160" height="60" viewBox="0 0 160 60">""" +
            """<rect width="160" height="60" fill="#ffffff"/>""" +
            """<text x="10" y="20" font-family="$font" font-size="12" font-weight="400" fill="#1f2933">First name</text>""" +
            """<text x="10" y="40" font-family="$font" font-size="12" font-weight="600" fill="#1f2933">Details</text></svg>"""
        val inherited = """<svg xmlns="http://www.w3.org/2000/svg" width="160" height="60" viewBox="0 0 160 60">""" +
            """<rect width="160" height="60" fill="#ffffff"/><g font-family="$font" font-size="12" fill="#1f2933">""" +
            """<text x="10" y="20">First name</text><text x="10" y="40" font-weight="600">Details</text></g></svg>"""
        val a = paint(explicit)
        assertTrue("the explicit form draws something", (0 until a.width).any { x -> (0 until a.height).any { y -> a.getRGB(x, y) != -1 } })
        assertTrue("the grouped attributes paint like the explicit ones", same(a, paint(inherited)))
    }

    fun testTheDefaultStrokeWidthIsOne() {
        val explicit = """<svg xmlns="http://www.w3.org/2000/svg" width="160" height="60" viewBox="0 0 160 60">""" +
            """<rect width="160" height="60" fill="#ffffff"/><rect x="10" y="10" width="100" height="30" fill="#ffffff" stroke="#b0bec5" stroke-width="1"/></svg>"""
        val default = explicit.replace(""" stroke-width="1"""", "")
        assertTrue(same(paint(explicit), paint(default)))
    }
}
