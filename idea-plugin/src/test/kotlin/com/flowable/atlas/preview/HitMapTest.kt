package com.flowable.atlas.preview

import com.flowable.atlas.diagram.ModelPicture
import com.flowable.atlas.model.ModelType
import junit.framework.TestCase
import java.awt.geom.Point2D

/** A click on a drawn picture maps back to the element under it — the innermost one. DEMO-* names. */
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
        val map = HitMap.of(ModelPicture.render(bpmn, "DEMO-P001.bpmn", ModelType.PROCESS))!!
        // the drawing starts PAD before the leftmost/topmost shape (x=100, y=60): document (0,0) is layout (76,36)
        fun at(x: Double, y: Double) = map.elementAt(Point2D.Double(x - 76, y - 36))
        assertEquals("start", at(115.0, 115.0))
        assertEquals("the task, not the sub-process around it", "review", at(300.0, 140.0))
        assertEquals("sub", at(450.0, 240.0))
        assertNull(at(150.0, 250.0))
    }

    fun testAFormCellIsItsField() {
        val form = """{"name":"DEMO-F001","rows":[{"cols":[{"id":"details","type":"panel","label":"Details","size":12,
            "extraSettings":{"layoutDefinition":{"rows":[{"cols":[{"id":"amount","type":"number","label":"Amount","size":12}]}]}}}]}]}""".toByteArray()
        val pic = ModelPicture.render(form, "DEMO-F001.form", ModelType.FORM)!!
        val map = HitMap.of(pic)!!
        val amount = pic.hotspots.single { it.id == "amount" }
        assertEquals("the field, not the panel around it", "amount", map.elementAt(Point2D.Double(amount.x + amount.width / 2, amount.y + amount.height / 2)))
    }

    fun testASubformSaysWhichFormItOpens() {
        val form = """{"name":"DEMO-F001","rows":[{"cols":[{"id":"amount","type":"number","label":"Amount","size":6},
            {"id":"addressSub","type":"subform","label":"Address","size":6,"extraSettings":{"formRef":{"key":"DEMO-F002"}}}]}]}""".toByteArray()
        val pic = ModelPicture.render(form, "DEMO-F001.form", ModelType.FORM)!!
        val map = HitMap.of(pic)!!
        fun centre(id: String) = pic.hotspots.single { it.id == id }.let { Point2D.Double(it.x + it.width / 2, it.y + it.height / 2) }
        assertEquals("form:DEMO-F002", map.refAt(centre("addressSub")))
        assertNull("a field opens nothing", map.refAt(centre("amount")))
        assertTrue(map.opensModels)
    }

    fun testARuleRowIsItsRule() {
        val dmn = """<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"><decision id="DEMO-D1" name="D"><decisionTable hitPolicy="FIRST">
            <input label="Total"><inputExpression><text>total</text></inputExpression></input><output name="ok"/>
            <rule id="r1"><inputEntry><text>&gt; 1</text></inputEntry><outputEntry><text>true</text></outputEntry></rule>
            <rule id="r2"><inputEntry><text>&lt;= 1</text></inputEntry><outputEntry><text>false</text></outputEntry></rule>
            </decisionTable></decision></definitions>""".toByteArray()
        val pic = ModelPicture.render(dmn, "DEMO-D1.dmn", ModelType.DECISION)!!
        val r2 = pic.hotspots.single { it.id == "r2" }
        assertEquals("r2", HitMap.of(pic)!!.elementAt(Point2D.Double(r2.x + 5, r2.y + r2.height / 2)))
    }

    fun testAPictureWithoutElementsHasNoMap() {
        assertNull(HitMap.of(null))
    }
}
