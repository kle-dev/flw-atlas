package com.flowable.atlas.diagram

import com.flowable.atlas.model.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiagramSvgRendererTest {

    private fun bytes(name: String): ByteArray =
        File(javaClass.classLoader.getResource("diagram/$name")!!.toURI()).readBytes()

    @Test
    fun rendersEverySilhouetteForABpmnProcess() {
        val g = XmlDiExtractor.extract(bytes("DEMO-onboarding.bpmn20.xml"), DiagramGeometry.Notation.BPMN)
        val svg = DiagramSvgRenderer.render(g)!!
        assertTrue(svg.startsWith("<svg"))
        for (marker in listOf("viewBox=", "<rect", "<circle", "<polygon", "<polyline", "marker-end")) {
            assertTrue("expected the SVG to contain $marker", svg.contains(marker))
        }
        assertTrue(svg.trimEnd().endsWith("</svg>"))
    }

    @Test
    fun outputIsDeterministic() {
        val g = XmlDiExtractor.extract(bytes("DEMO-onboarding.bpmn20.xml"), DiagramGeometry.Notation.BPMN)
        assertEquals(DiagramSvgRenderer.render(g), DiagramSvgRenderer.render(g))
    }

    @Test
    fun labelsAreXmlEscaped() {
        val g = DiagramGeometry(
            shapes = listOf(DiaShape("t", ShapeKind.TASK, 0.0, 0.0, 120.0, 60.0, "A<X>B&C")),
            edges = emptyList(),
            notation = DiagramGeometry.Notation.BPMN,
        )
        val svg = DiagramSvgRenderer.render(g)!!
        assertTrue(svg.contains("A&lt;X&gt;B&amp;C"))
        assertFalse(svg.contains("A<X>"))
    }

    @Test
    fun emptyGeometryRendersNull() {
        assertNull(DiagramSvgRenderer.render(DiagramGeometry(emptyList(), emptyList(), DiagramGeometry.Notation.BPMN)))
    }

    @Test
    fun facadePicksSourceByTypeAndFormat() {
        assertNotNullSvg(DiagramRenderer.renderSvg(bytes("DEMO-onboarding.bpmn20.xml"), "DEMO-onboarding.bpmn20.xml", ModelType.PROCESS))
        assertNotNullSvg(DiagramRenderer.renderSvg(bytes("DEMO-onboarding.json"), "DEMO-onboarding.json", ModelType.PROCESS))
        // a non-diagram model type has no diagram, whatever its bytes
        assertNull(DiagramRenderer.renderSvg(bytes("DEMO-onboarding.json"), "DEMO-onboarding.form", ModelType.FORM))
    }

    private fun assertNotNullSvg(svg: String?) {
        assertTrue("expected a rendered <svg>", svg != null && svg.startsWith("<svg"))
    }
}
