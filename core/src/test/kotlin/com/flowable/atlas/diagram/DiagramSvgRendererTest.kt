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

    // ---- type glyphs + Design names -------------------------------------------------------------

    private fun taskTypesSvg(): String =
        DiagramSvgRenderer.render(
            XmlDiExtractor.extract(bytes("DEMO-tasktypes.bpmn20.xml"), DiagramGeometry.Notation.BPMN),
        )!!

    @Test
    fun eachElementCarriesItsTypeGlyph() {
        val svg = taskTypesSvg()
        val icons = Regex("""data-icon="([a-z-]+)"""").findAll(svg).map { it.groupValues[1] }.toSet()
        // one per resolution route: XML tag, flowable:type, Design stencil, and an event definition
        for (expected in listOf(
            "user",                 // <userTask> — from the tag
            "service",              // <serviceTask flowable:class=…> — from the tag
            "service-registry",     // <design:stencilid>ServiceRegistryTask — from the Design stencil
            "agent", "http", "mail", // flowable:type=agent / http / mail
            "data-object",          // <design:stencilid>DataObjectCreateTask
            "script",               // <scriptTask>
            "timer", "error",       // timerEventDefinition / errorEventDefinition
        )) {
            assertTrue("expected data-icon=\"$expected\" in the SVG, got $icons", expected in icons)
        }
    }

    @Test
    fun everyShapeIsTitledWithItsDesignTypeName() {
        val svg = taskTypesSvg()
        val titles = Regex("<title>(.*?)</title>").findAll(svg).map { it.groupValues[1] }.toList()
        // Design's own palette wording — these are the strings a modeller sees in the Design palette
        assertTrue(titles.contains("Lookup customer — Service registry task"))
        assertTrue(titles.contains("Ask the agent — AI Agent"))
        assertTrue(titles.contains("Create region — Data object create"))
        assertTrue(titles.contains("Send mail — Email task"))
        assertTrue(titles.contains("Approve order — User task"))
        assertTrue(titles.contains("Fulfil — Call activity"))
        assertTrue(titles.contains("Timeout — Timer event"))
        // one <title> per shape, so hovering anywhere on the canvas explains what is under the cursor
        assertEquals(14, titles.size)
    }

    @Test
    fun shapesAndEdgesCarryTheirElementIdForInteractivity() {
        // data-el on shape groups is the click contract with the explorer (element ↔ details routing)
        assertTrue(taskTypesSvg().contains("""<g data-el="t8""""))
        // edges are grouped too, with an invisible fat stroke as the actual click target
        // (the tasktypes fixture has no DI edges — onboarding does)
        val svg = DiagramSvgRenderer.render(
            XmlDiExtractor.extract(bytes("DEMO-onboarding.bpmn20.xml"), DiagramGeometry.Notation.BPMN),
        )!!
        val edgeGroups = Regex("""<g data-el="[^"]+"><polyline""").findAll(svg).count()
        assertTrue("expected clickable edge groups, got $edgeGroups", edgeGroups > 0)
        assertTrue(svg.contains("""stroke-opacity="0" stroke-width="14" pointer-events="stroke""""))
    }

    @Test
    fun callActivityIsThickBorderedRatherThanAMarkedSubProcess() {
        val g = XmlDiExtractor.extract(bytes("DEMO-tasktypes.bpmn20.xml"), DiagramGeometry.Notation.BPMN)
        val call = g.shapes.single { it.elementId == "t8" }
        assertEquals(ShapeKind.CALL_ACTIVITY, call.kind)
        assertEquals("Call activity", call.typeLabel)
        assertNull("a call activity has no type glyph — its thick border is the notation", call.icon)
        assertTrue("expected the thick call-activity outline", taskTypesSvg().contains("""stroke-width="3.5""""))
    }

    @Test
    fun nonInterruptingBoundaryEventIsDashedAndMultiInstanceIsMarked() {
        val g = XmlDiExtractor.extract(bytes("DEMO-tasktypes.bpmn20.xml"), DiagramGeometry.Notation.BPMN)
        assertTrue(DiaMarker.NON_INTERRUPTING in g.shapes.single { it.elementId == "b1" }.markers)
        assertTrue(DiaMarker.MI_PARALLEL in g.shapes.single { it.elementId == "t10" }.markers)
        assertTrue(taskTypesSvg().contains("""stroke-dasharray="4 3""""))
    }

    @Test
    fun aDesignWorkspaceModelResolvesItsStencilToTheSameGlyph() {
        // the ORYX path carries the stencil id directly, so it must reach the same icon as the XML path
        val g = OryxJsonDiExtractor.extract(bytes("DEMO-onboarding.json"), DiagramGeometry.Notation.BPMN)
        val task = g.shapes.single { it.icon == DiaIcon.USER }
        assertEquals("User task", task.typeLabel)
        assertTrue(DiagramSvgRenderer.render(g)!!.contains("""data-icon="user""""))
    }
}
