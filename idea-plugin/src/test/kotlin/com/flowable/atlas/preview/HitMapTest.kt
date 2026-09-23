package com.flowable.atlas.preview

import com.flowable.atlas.diagram.DiagramRenderer
import com.flowable.atlas.model.ModelType
import junit.framework.TestCase
import java.awt.geom.Point2D

/** A click on the drawn diagram maps back to the element under it — the innermost one. DEMO-* names. */
class HitMapTest : TestCase() {

    private val bpmn = """
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                     xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                     xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC">
          <process id="DEMO-P001">
            <subProcess id="sub"><userTask id="review" name="Review"/></subProcess>
            <startEvent id="start"/>
          </process>
          <bpmndi:BPMNDiagram><bpmndi:BPMNPlane bpmnElement="DEMO-P001">
            <bpmndi:BPMNShape bpmnElement="start"><omgdc:Bounds x="100" y="100" width="30" height="30"/></bpmndi:BPMNShape>
            <bpmndi:BPMNShape bpmnElement="sub" isExpanded="true"><omgdc:Bounds x="200" y="60" width="300" height="200"/></bpmndi:BPMNShape>
            <bpmndi:BPMNShape bpmnElement="review"><omgdc:Bounds x="250" y="100" width="100" height="80"/></bpmndi:BPMNShape>
          </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
        </definitions>
    """.trimIndent().toByteArray()

    fun testTheInnermostShapeUnderThePointIsTheElement() {
        val svg = DiagramRenderer.renderSvg(bpmn, "DEMO-P001.bpmn", ModelType.PROCESS)!!
        val map = HitMap.of(svg, bpmn, "DEMO-P001.bpmn", ModelType.PROCESS)!!
        // the drawing starts PAD before the leftmost/topmost shape (x=100, y=60): document (0,0) is layout (76,36)
        fun at(x: Double, y: Double) = map.elementAt(Point2D.Double(x - 76, y - 36))
        assertEquals("start", at(115.0, 115.0))
        assertEquals("the task, not the sub-process around it", "review", at(300.0, 140.0))
        assertEquals("sub", at(450.0, 240.0))
        assertNull(at(150.0, 250.0))
    }

    fun testAFormHasNoMap() {
        assertNull(HitMap.of("<svg viewBox=\"0 0 10 10\"/>", "{}".toByteArray(), "x.form", ModelType.FORM))
    }
}
