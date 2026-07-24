package com.flowable.atlas.diagram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [DiagramArtifacts] is the additive generation step. These assert it renders diagram-bearing nodes and
 * — crucially for the golden invariant — emits nothing for models without DI or of a non-diagram type,
 * so a project without layout produces no diagram files.
 */
class DiagramArtifactsTest {

    private val root: File by lazy { File(javaClass.classLoader.getResource("diagram")!!.toURI()) }

    private fun resultWith(vararg nodes: Map<String, Any?>): Map<String, Any?> =
        mapOf("graph" to mapOf("nodes" to nodes.toList()))

    private fun node(type: String, key: String, file: String): Map<String, Any?> =
        mapOf("type" to type, "key" to key, "file" to file)

    @Test
    fun rendersProcessNodeToSvgArtifact() {
        val out = DiagramArtifacts.render(
            resultWith(node("process", "DEMO-onboarding", "DEMO-onboarding.bpmn20.xml")),
            root,
        )
        assertEquals(setOf("DEMO-onboarding.svg"), out.keys)
        assertTrue(out.getValue("DEMO-onboarding.svg").startsWith("<svg"))
    }

    @Test
    fun rendersCaseAndDecisionNodes() {
        val out = DiagramArtifacts.render(
            resultWith(
                node("case", "DEMO-reviewCase", "DEMO-review.cmmn"),
                node("decision", "DEMO-risk", "DEMO-risk.dmn"),
            ),
            root,
        )
        assertEquals(setOf("DEMO-reviewCase.svg", "DEMO-risk.svg"), out.keys)
    }

    @Test
    fun skipsNonDiagramTypesModelsWithoutDiAndUnreadableFiles() {
        val out = DiagramArtifacts.render(
            resultWith(
                node("form", "DEMO-form", "DEMO-onboarding.bpmn20.xml"),   // non-diagram type
                node("process", "DEMO-nolayout", "DEMO-nolayout.bpmn"),     // no DI
                node("process", "DEMO-missing", "does-not-exist.bpmn"),     // unreadable
            ),
            root,
        )
        assertTrue("expected no diagram artifacts, got ${out.keys}", out.isEmpty())
    }

    @Test
    fun emptyOrGraphlessResultYieldsNothing() {
        assertTrue(DiagramArtifacts.render(emptyMap(), root).isEmpty())
        assertTrue(DiagramArtifacts.render(mapOf("graph" to mapOf("nodes" to emptyList<Any?>())), root).isEmpty())
    }
}
