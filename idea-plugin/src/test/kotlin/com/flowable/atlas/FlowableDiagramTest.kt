package com.flowable.atlas

import com.flowable.atlas.model.ModelType
import com.flowable.atlas.usage.DiagramSvgCache
import com.flowable.atlas.usage.FlowableDiagram
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The diagram resolver. A Design-export model file with a bundled sibling `.svg` resolves to it (best
 * fidelity); a model without one is rendered from its diagram-interchange layout by the shared `:core`
 * engine (newer Design exports no longer bundle a `.svg`); a non-diagram model, or one with no layout,
 * resolves to nothing so the gutter marker stays self-limiting. DEMO-* placeholder names — repo public.
 */
class FlowableDiagramTest : BasePlatformTestCase() {

    fun testResolvesSiblingSvg() {
        val model = myFixture.addFileToProject("bpmn-models/DEMO-onboarding.bpmn", "<definitions/>").virtualFile
        myFixture.addFileToProject("bpmn-models/DEMO-onboarding.svg", "<svg/>")

        val svg = FlowableDiagram.siblingSvg(model)
        assertNotNull("a model file with a sibling .svg should resolve to it", svg)
        assertEquals("DEMO-onboarding.svg", svg!!.name)
    }

    fun testNoSiblingSvgReturnsNull() {
        val model = myFixture.addFileToProject("bar/DEMO-order.bpmn", "<definitions/>").virtualFile
        assertNull(
            "a model file without a sibling .svg (e.g. a deployment BAR) returns null",
            FlowableDiagram.siblingSvg(model),
        )
    }

    fun testMatchIsCaseInsensitive() {
        val model = myFixture.addFileToProject("form-models/DEMO-claim.form", "{}").virtualFile
        myFixture.addFileToProject("form-models/DEMO-claim.SVG", "<svg/>")

        assertNotNull(
            "the .svg extension is matched case-insensitively",
            FlowableDiagram.siblingSvg(model),
        )
    }

    fun testRendersDiagramFromDiWhenNoSiblingSvg() {
        val model = myFixture.addFileToProject("bpmn-models/DEMO-onboarding.bpmn20.xml", BPMN_WITH_DI).virtualFile
        val svg = DiagramSvgCache.getInstance(project).resolveDiagram(model, ModelType.PROCESS)
        assertNotNull("a diagram-bearing model with no sibling .svg is rendered from its DI", svg)
        assertTrue("the rendered file is an .svg", svg!!.name.endsWith(".svg"))
        assertTrue("the rendered content is an SVG document", String(svg.contentsToByteArray()).startsWith("<svg"))
    }

    fun testSiblingSvgWinsOverRendering() {
        val model = myFixture.addFileToProject("bpmn-models/DEMO-both.bpmn20.xml", BPMN_WITH_DI).virtualFile
        myFixture.addFileToProject("bpmn-models/DEMO-both.svg", "<svg id=\"bundled\"/>")
        val svg = DiagramSvgCache.getInstance(project).resolveDiagram(model, ModelType.PROCESS)
        assertEquals("the bundled sibling .svg is preferred", "DEMO-both.svg", svg!!.name)
    }

    fun testNonDiagramTypeResolvesToNull() {
        val model = myFixture.addFileToProject("form-models/DEMO-claim2.form", "{}").virtualFile
        assertNull(
            "a form has no diagram to render",
            DiagramSvgCache.getInstance(project).resolveDiagram(model, ModelType.FORM),
        )
    }

    fun testDiagramBearingModelWithoutLayoutResolvesToNull() {
        val model = myFixture.addFileToProject("bpmn-models/DEMO-nolayout.bpmn20.xml", "<definitions><process id=\"p\"/></definitions>").virtualFile
        assertNull(
            "a process with no DI layout has nothing to render",
            DiagramSvgCache.getInstance(project).resolveDiagram(model, ModelType.PROCESS),
        )
    }

    fun testHasOpenableDiagramIsCheapAndSelfLimiting() {
        val process = myFixture.addFileToProject("bpmn-models/DEMO-p.bpmn20.xml", "<definitions/>").virtualFile
        assertTrue("a process type always warrants a marker (rendered lazily)", FlowableDiagram.hasOpenableDiagram(process, ModelType.PROCESS))
        val form = myFixture.addFileToProject("form-models/DEMO-f.form", "{}").virtualFile
        assertFalse("a form with no sibling .svg warrants no diagram marker", FlowableDiagram.hasOpenableDiagram(form, ModelType.FORM))
    }

    private companion object {
        val BPMN_WITH_DI = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                         xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC"
                         xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI">
              <process id="DEMO-onboarding" name="DEMO Onboarding">
                <startEvent id="start"/>
                <userTask id="review" name="Review"/>
                <endEvent id="end"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
                <sequenceFlow id="f2" sourceRef="review" targetRef="end"/>
              </process>
              <bpmndi:BPMNDiagram id="D">
                <bpmndi:BPMNPlane bpmnElement="DEMO-onboarding">
                  <bpmndi:BPMNShape id="s1" bpmnElement="start"><omgdc:Bounds x="100" y="100" width="30" height="30"/></bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="s2" bpmnElement="review"><omgdc:Bounds x="180" y="85" width="100" height="60"/></bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="s3" bpmnElement="end"><omgdc:Bounds x="330" y="100" width="30" height="30"/></bpmndi:BPMNShape>
                  <bpmndi:BPMNEdge id="e1" bpmnElement="f1"><omgdi:waypoint x="130" y="115"/><omgdi:waypoint x="180" y="115"/></bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="e2" bpmnElement="f2"><omgdi:waypoint x="280" y="115"/><omgdi:waypoint x="330" y="115"/></bpmndi:BPMNEdge>
                </bpmndi:BPMNPlane>
              </bpmndi:BPMNDiagram>
            </definitions>
        """.trimIndent()
    }
}
