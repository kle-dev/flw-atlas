package com.flowable.atlas.diagram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A Design decision table carries no `dmndi` layout, so [DiagramRenderer] can't draw it —
 * [DmnTableSvgRenderer] paints the table itself instead. DEMO-* names — repo public.
 */
class DmnTableSvgRendererTest {

    private fun bytes(path: String): ByteArray =
        File(javaClass.classLoader.getResource(path)!!.toURI()).readBytes()

    private fun eligibilitySvg(): String = DmnTableSvgRenderer.renderSvg(bytes("dmntable/DEMO-eligibility.dmn"))!!

    @Test
    fun rendersHeadersRulesAndHitPolicy() {
        val svg = eligibilitySvg()
        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.trimEnd().endsWith("</svg>"))
        for (expected in listOf(
            "Eligibility &amp; risk",     // the decision name, XML-escaped
            "Hit policy: FIRST",
            "Input", "Output", "Annotation",
            "Order total", "order.total", // a labelled input shows the expression underneath
            "customer.segment",           // an unlabelled input falls back to its expression
            "Approved", "approved",
            "reviewLevel",
            // cell text is escaped exactly like every other Atlas SVG label
            "&gt; 10000", "&lt;= 10000", "&quot;RETAIL&quot;", "&quot;SENIOR&quot;",
            "Large orders always need a review.",
        )) {
            assertTrue("expected \"$expected\" in the rendered table", svg.contains(expected))
        }
        // one row per rule, numbered
        for (n in 1..3) assertTrue(svg.contains(">$n</text>"))
    }

    @Test
    fun anEmptyInputEntryReadsAsAnyValue() {
        // rule 1 leaves the segment input empty — DMN treats that as "matches anything"
        assertTrue(eligibilitySvg().contains(">-</text>"))
    }

    @Test
    fun neverEmitsRawMarkupFromTheModel() {
        val svg = eligibilitySvg()
        assertFalse("cell text must be escaped", svg.contains("> 10000<"))
        assertFalse(svg.contains("<= 10000"))
    }

    @Test
    fun outputIsDeterministic() {
        assertEquals(eligibilitySvg(), eligibilitySvg())
    }

    @Test
    fun aDecisionWithoutATableRendersNull() {
        // DEMO-risk is a DRD: shapes + requirements, no decision table. DiagramRenderer draws that one
        // from its dmndi layout, so the table renderer must stay out of the way.
        assertNull(DmnTableSvgRenderer.renderSvg(bytes("diagram/DEMO-risk.dmn")))
        assertTrue(
            DiagramRenderer.renderSvg(
                bytes("diagram/DEMO-risk.dmn"),
                "DEMO-risk.dmn",
                com.flowable.atlas.model.ModelType.DECISION,
            ) != null,
        )
    }

    @Test
    fun garbageBytesRenderNullRatherThanThrow() {
        assertNull(DmnTableSvgRenderer.renderSvg("not xml at all".toByteArray()))
        assertNull(DmnTableSvgRenderer.renderSvg(ByteArray(0)))
    }

    @Test
    fun theTableRendererIsNotWiredIntoTheSharedDiagramPipeline() {
        // The explorer payload and the Diagrams (SVG) artifacts must keep showing a decision's rules as
        // the frontend's own HTML table — only the IDE gutter falls back to the painted table.
        assertNull(
            DiagramRenderer.renderSvg(
                bytes("dmntable/DEMO-eligibility.dmn"),
                "DEMO-eligibility.dmn",
                com.flowable.atlas.model.ModelType.DECISION,
            ),
        )
    }
}
