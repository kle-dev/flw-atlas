package com.flowable.atlas.diagram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies both DI extractors resolve the same geometry from the two on-disk shapes Flowable ships:
 * deployment XML (`bpmndi`/`cmmndi`/`dmndi`) via [XmlDiExtractor] and Design-workspace ORYX JSON via
 * [OryxJsonDiExtractor]. Fixtures use `DEMO-*` names only.
 */
class DiagramExtractionTest {

    private fun bytes(name: String): ByteArray =
        File(javaClass.classLoader.getResource("diagram/$name")!!.toURI()).readBytes()

    @Test
    fun bpmnXmlShapesAndEdges() {
        val g = XmlDiExtractor.extract(bytes("DEMO-onboarding.bpmn20.xml"), DiagramGeometry.Notation.BPMN)
        assertEquals(4, g.shapes.size)
        assertEquals(3, g.edges.size)
        assertEquals(
            setOf(ShapeKind.EVENT_START, ShapeKind.TASK, ShapeKind.GATEWAY_EXCLUSIVE, ShapeKind.EVENT_END),
            g.shapes.map { it.kind }.toSet(),
        )
        assertTrue(g.edges.all { it.kind == EdgeKind.SEQUENCE_FLOW })

        val start = g.shapes.first { it.elementId == "start" }
        assertEquals(100.0, start.x, 0.0)
        assertEquals(100.0, start.y, 0.0)
        assertEquals(30.0, start.width, 0.0)
        assertEquals("Review application", g.shapes.first { it.elementId == "review" }.label)
        assertEquals(2, g.edges.first { it.elementId == "f1" }.waypoints.size)
    }

    @Test
    fun bpmnOryxJsonShapesAndEdges() {
        val g = OryxJsonDiExtractor.extract(bytes("DEMO-onboarding.json"), DiagramGeometry.Notation.BPMN)
        assertEquals(3, g.shapes.size)   // start, task, end (flows are edges, not shapes)
        assertEquals(2, g.edges.size)
        assertEquals(
            setOf(ShapeKind.EVENT_START, ShapeKind.TASK, ShapeKind.EVENT_END),
            g.shapes.map { it.kind }.toSet(),
        )
        val start = g.shapes.first { it.elementId == "sid-start" }
        assertEquals(100.0, start.x, 0.0)
        assertEquals(30.0, start.width, 0.0)
        assertEquals("Review application", g.shapes.first { it.elementId == "sid-review" }.label)
        // both flows resolve source→target centres, so every edge has two waypoints
        assertTrue(g.edges.all { it.waypoints.size == 2 })
    }

    @Test
    fun oryxNestedBoundsAreFoldedToAbsolute() {
        val nested = """
            {"resourceId":"canvas","stencil":{"id":"BPMNDiagram"},"childShapes":[
              {"resourceId":"pool","stencil":{"id":"Pool"},
               "bounds":{"upperLeft":{"x":10,"y":10},"lowerRight":{"x":210,"y":110}},
               "properties":{"name":"Lane A"},"childShapes":[
                 {"resourceId":"t1","stencil":{"id":"UserTask"},
                  "bounds":{"upperLeft":{"x":20,"y":20},"lowerRight":{"x":120,"y":60}},
                  "properties":{"name":"Do it"},"childShapes":[]}
              ]}
            ]}
        """.trimIndent()
        val g = OryxJsonDiExtractor.extract(nested.toByteArray(), DiagramGeometry.Notation.BPMN)
        val task = g.shapes.first { it.elementId == "t1" }
        assertEquals(30.0, task.x, 0.0)   // 10 (pool) + 20 (relative)
        assertEquals(30.0, task.y, 0.0)
        assertEquals(100.0, task.width, 0.0)
        assertEquals(40.0, task.height, 0.0)
        assertEquals(ShapeKind.POOL, g.shapes.first { it.elementId == "pool" }.kind)
    }

    @Test
    fun cmmnXmlResolvesPlanItemDefinitions() {
        val g = XmlDiExtractor.extract(bytes("DEMO-review.cmmn"), DiagramGeometry.Notation.CMMN)
        assertEquals(3, g.shapes.size)
        assertEquals(ShapeKind.CMMN_STAGE, g.shapes.first { it.elementId == "cpm" }.kind)
        // planItem → definitionRef → humanTask / milestone
        assertEquals(ShapeKind.CMMN_TASK, g.shapes.first { it.elementId == "pi_task" }.kind)
        assertEquals(ShapeKind.CMMN_MILESTONE, g.shapes.first { it.elementId == "pi_ms" }.kind)
        assertEquals("Completed", g.shapes.first { it.elementId == "pi_ms" }.label)
    }

    @Test
    fun dmnXmlDrdShapesAndRequirementEdge() {
        val g = XmlDiExtractor.extract(bytes("DEMO-risk.dmn"), DiagramGeometry.Notation.DMN)
        assertEquals(2, g.shapes.size)
        assertEquals(ShapeKind.DMN_INPUT_DATA, g.shapes.first { it.elementId == "in_amount" }.kind)
        assertEquals(ShapeKind.DMN_DECISION, g.shapes.first { it.elementId == "dec_risk" }.kind)
        assertEquals(1, g.edges.size)
        assertEquals(EdgeKind.DMN_REQUIREMENT, g.edges.first().kind)
    }

    @Test
    fun modelWithoutDiYieldsEmptyGeometry() {
        val g = XmlDiExtractor.extract(bytes("DEMO-nolayout.bpmn"), DiagramGeometry.Notation.BPMN)
        assertTrue(g.isEmpty())
    }

    @Test
    fun malformedInputIsToleratedNotThrown() {
        val g = XmlDiExtractor.extract("<not-xml".toByteArray(), DiagramGeometry.Notation.BPMN)
        assertNotNull(g)
        assertTrue(g.isEmpty())
    }
}
