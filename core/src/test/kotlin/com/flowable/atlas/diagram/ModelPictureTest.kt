package com.flowable.atlas.diagram

import com.flowable.atlas.model.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [ModelPicture] is the one way Atlas draws a model — the explorer, the diagrams folder and the plugin's
 * preview all call it — and every picture says where its elements are. DEMO-* names — repo public.
 */
class ModelPictureTest {

    private fun file(path: String) = File(javaClass.classLoader.getResource(path)!!.toURI())
    private fun render(path: String, type: ModelType) = file(path).let { ModelPicture.render(it.readBytes(), it.name, type) }

    @Test
    fun eachModelTypeGetsItsPicture() {
        assertEquals(Picture.Kind.DIAGRAM, render("diagram/DEMO-onboarding.bpmn20.xml", ModelType.PROCESS)!!.kind)
        assertEquals(Picture.Kind.DIAGRAM, render("diagram/DEMO-review.cmmn", ModelType.CASE)!!.kind)
        assertEquals(Picture.Kind.WIREFRAME, render("formlayout/DEMO-onboarding.form", ModelType.FORM)!!.kind)
        // a decision table without a layout is drawn from its rules
        assertEquals(Picture.Kind.DECISION_TABLE, render("dmntable/DEMO-eligibility.dmn", ModelType.DECISION)!!.kind)
        assertNull("a process without a layout has no picture", render("diagram/DEMO-nolayout.bpmn", ModelType.PROCESS))
        assertNull("a service has no picture", ModelPicture.render("{}".toByteArray(), "x.service", ModelType.SERVICE))
    }

    @Test
    fun theSvgSizeIsItsViewBoxSoDocumentPointsAreViewBoxPoints() {
        for ((path, type) in listOf("diagram/DEMO-onboarding.bpmn20.xml" to ModelType.PROCESS,
            "formlayout/DEMO-onboarding.form" to ModelType.FORM, "dmntable/DEMO-eligibility.dmn" to ModelType.DECISION)) {
            val pic = render(path, type)!!
            val head = pic.svg.substringBefore(">")
            val w = Regex("""width="([\d.]+)"""").find(head)!!.groupValues[1].toDouble()
            val h = Regex("""height="([\d.]+)"""").find(head)!!.groupValues[1].toDouble()
            assertEquals(path, pic.viewBox.width, w, 0.01)
            assertEquals(path, pic.viewBox.height, h, 0.01)
        }
    }

    @Test
    fun aDiagramShapeIsAHotspotAndTheInnermostWins() {
        val pic = render("diagram/DEMO-review.cmmn", ModelType.CASE)!!
        assertTrue(pic.hotspots.isNotEmpty())
        // every hotspot is a data-el of the drawing
        for (hs in pic.hotspots) assertTrue(hs.id, pic.svg.contains("""data-el="${hs.id}""""))
        // the smallest box under a point: a task inside a stage is the task
        val smallest = pic.hotspots.minByOrNull { it.width * it.height }!!
        assertEquals(smallest.id, pic.hotspotAt(smallest.x + smallest.width / 2, smallest.y + smallest.height / 2)!!.id)
    }

    @Test
    fun aDecisionTableRuleIsAHotspot() {
        val pic = render("dmntable/DEMO-eligibility.dmn", ModelType.DECISION)!!
        assertTrue("a rule row per rule with an id", pic.hotspots.isNotEmpty())
        val first = pic.hotspots.first()
        assertTrue(pic.svg.contains("""<g data-el="${first.id}" tabindex="0" role="button">"""))
        assertNotNull(pic.hotspotAt(first.x + 5, first.y + first.height / 2))
    }

    @Test
    fun aNodeTypeMapsToTheModelTypeItIsDrawnAs() {
        assertEquals(ModelType.PAGE, ModelPicture.typeOfNode("page"))
        assertNull(ModelPicture.typeOfNode("service"))
    }
}
