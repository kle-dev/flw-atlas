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

    /**
     * A label broke only at spaces, so a name that is one long token — an expression, a camelCase name —
     * became one line twice as wide as its task, printed over its neighbours' labels.
     */
    @Test
    fun aLongNameOrExpressionBreaksInsideItsShape() {
        val g = DiagramGeometry(
            shapes = listOf(
                DiaShape("t1", ShapeKind.TASK, 0.0, 0.0, 100.0, 80.0, "setMetadataOfAllDocumentsNow"),
                DiaShape("t2", ShapeKind.TASK, 130.0, 0.0, 100.0, 80.0, "Check \${vars:get(root.someReference)}"),
                DiaShape("t3", ShapeKind.TASK, 260.0, 0.0, 100.0, 80.0, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
                DiaShape("t4", ShapeKind.TASK, 390.0, 0.0, 100.0, 80.0, "Check the \${vars:get(root.someReference)} now please"),
            ),
            edges = emptyList(),
            notation = DiagramGeometry.Notation.BPMN,
        )
        val svg = DiagramSvgRenderer.render(g)!!
        fun lines(id: String) = Regex("<text[^>]*>([^<]*)</text>").findAll(group(svg, id)).map {
            it.groupValues[1].replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&#39;", "'")
        }.toList()
        val maxChars = 13   // what a 100-px task fits: (100 - 8) / 6.6
        for (id in listOf("t1", "t2", "t3", "t4")) {
            val ls = lines(id)
            assertTrue("$id: $ls", ls.isNotEmpty() && ls.size <= 4)
            assertTrue("$id: every line fits its shape: $ls", ls.all { it.length <= maxChars })
        }
        assertEquals("a camelCase name breaks at its humps", listOf("setMetadataOf", "AllDocuments", "Now"), lines("t1"))
        val t2 = lines("t2")
        assertEquals("the words before the expression keep their own line", "Check", t2.first())
        assertEquals("nothing is lost", "Check \${vars:get(root.someReference)}", t2.first() + " " + t2.drop(1).joinToString(""))
        assertTrue("a token with nowhere to break is cut, and an overlong label still ends in …", lines("t3").last().endsWith("…"))
        assertEquals(
            "a label cut after a short line says so too",
            listOf("Check the", "\${vars:get(", "root.some", "Reference)}…"), lines("t4"),
        )
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
    fun onlyACollapsedSubProcessWearsThePlusMarker() {
        val xml = """<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                       xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                       xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC">
              <process id="p">
                <subProcess id="open" name="Expanded"><userTask id="inner"/></subProcess>
                <subProcess id="closed" name="Collapsed"/>
              </process>
              <bpmndi:BPMNDiagram><bpmndi:BPMNPlane bpmnElement="p">
                <bpmndi:BPMNShape bpmnElement="open" isExpanded="true"><omgdc:Bounds x="0" y="0" width="300" height="200"/></bpmndi:BPMNShape>
                <bpmndi:BPMNShape bpmnElement="inner"><omgdc:Bounds x="20" y="20" width="100" height="80"/></bpmndi:BPMNShape>
                <bpmndi:BPMNShape bpmnElement="closed" isExpanded="false"><omgdc:Bounds x="400" y="0" width="100" height="80"/></bpmndi:BPMNShape>
              </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
            </definitions>""".toByteArray()
        val g = XmlDiExtractor.extract(xml, DiagramGeometry.Notation.BPMN)
        assertTrue(DiaMarker.COLLAPSED in g.shapes.single { it.elementId == "closed" }.markers)
        assertTrue(DiaMarker.COLLAPSED !in g.shapes.single { it.elementId == "open" }.markers)
        val svg = DiagramSvgRenderer.render(g)!!
        // the [+] box is a 14×14 rect; exactly one, under the collapsed sub-process
        assertEquals(1, Regex("""width="14" height="14"""").findAll(svg).count())
    }

    @Test
    fun nonInterruptingBoundaryEventIsDashedAndMultiInstanceIsMarked() {
        val g = XmlDiExtractor.extract(bytes("DEMO-tasktypes.bpmn20.xml"), DiagramGeometry.Notation.BPMN)
        assertTrue(DiaMarker.NON_INTERRUPTING in g.shapes.single { it.elementId == "b1" }.markers)
        assertTrue(DiaMarker.MI_PARALLEL in g.shapes.single { it.elementId == "t10" }.markers)
        assertTrue(taskTypesSvg().contains("""stroke-dasharray="4 3""""))
    }

    // ---- notation: decision services, event listeners, event sub-processes ---------------------------

    /** The markup of one element's group: from its `<g data-el>` to the next element's. */
    private fun group(svg: String, id: String): String {
        val start = svg.indexOf("""<g data-el="$id"""")
        assertTrue("no group for $id", start >= 0)
        val end = svg.indexOf("<g data-el=", start + 1).takeIf { it > 0 } ?: svg.length
        return svg.substring(start, end)
    }

    /**
     * A decision service was classed as a decision: an opaque box drawn after the edges, so every
     * information requirement between its decisions vanished under it, and its name floated mid-box.
     */
    @Test
    fun aDecisionServiceIsAContainerDrawnUnderTheRequirementsThatCrossIt() {
        val g = XmlDiExtractor.extract(bytes("DEMO-scoring.dmn"), DiagramGeometry.Notation.DMN)
        val service = g.shapes.single { it.elementId == "DEMO-scoring" }
        assertEquals(ShapeKind.DMN_DECISION_SERVICE, service.kind)
        assertEquals("Decision service", service.typeLabel)
        assertEquals("the DI's divider line", listOf(Point(100.0, 170.0), Point(500.0, 170.0)), service.divider)

        val svg = DiagramSvgRenderer.render(g)!!
        val at = svg.indexOf("""<g data-el="DEMO-scoring"""")
        for (edge in listOf("ir_country", "ir_amount")) {
            val e = svg.indexOf("""<g data-el="$edge"""")
            assertTrue("$edge is drawn after the service, so it stays visible", e > at)
            assertTrue(group(svg, edge).contains("marker-end"))
        }
        val box = group(svg, "DEMO-scoring")
        assertTrue("a rounded container: $box", box.contains("""rx="12" ry="12" fill="#f7f9fa""""))
        assertTrue("the divider between output and encapsulated decisions", box.contains("""<polyline points="100,170 500,170""""))
        assertTrue("the name sits top-left: $box", box.contains("""<text x="110" y="58" text-anchor="start""""))
        assertTrue(box.contains(">Scoring Service</text>"))
    }

    @Test
    fun aBusinessKnowledgeModelAndAKnowledgeSourceHaveTheirOwnSilhouettes() {
        val svg = DiagramSvgRenderer.render(XmlDiExtractor.extract(bytes("DEMO-scoring.dmn"), DiagramGeometry.Notation.DMN))!!
        assertTrue("cut corners", group(svg, "bkm_rules").contains("""<path d="M574,80 L680,80 L680,126 L666,140"""))
        assertTrue("a wavy bottom", group(svg, "ks_policy").contains("""<path d="M560,200 L680,200 L680,250 C"""))
        assertFalse(group(svg, "bkm_rules").contains("<rect"))
    }

    /** A Design workspace DRD holds the service's decisions in two section shapes, which are not drawn. */
    @Test
    fun aDesignDecisionServiceDrawsItsDividerWhereTheEncapsulatedSectionStarts() {
        val json = """
            {"resourceId":"canvas","stencil":{"id":"DMNDiagram"},"childShapes":[
              {"resourceId":"svc","stencil":{"id":"ExpandedDecisionService"},"properties":{"name":"Scoring Service"},
               "bounds":{"upperLeft":{"x":100,"y":40},"lowerRight":{"x":500,"y":340}},"childShapes":[
                 {"resourceId":"out","stencil":{"id":"OutputDecisionsDecisionServiceSection"},
                  "bounds":{"upperLeft":{"x":0,"y":0},"lowerRight":{"x":400,"y":130}},"childShapes":[
                    {"resourceId":"dec_score","stencil":{"id":"Decision"},"properties":{"name":"Risk score"},
                     "bounds":{"upperLeft":{"x":140,"y":40},"lowerRight":{"x":260,"y":100}},"childShapes":[]}]},
                 {"resourceId":"enc","stencil":{"id":"EncapsulatedDecisionsDecisionServiceSection"},
                  "bounds":{"upperLeft":{"x":0,"y":130},"lowerRight":{"x":400,"y":300}},"childShapes":[
                    {"resourceId":"dec_a","stencil":{"id":"Decision"},"properties":{"name":"Country risk"},
                     "bounds":{"upperLeft":{"x":40,"y":70},"lowerRight":{"x":160,"y":130}},"childShapes":[]}]}
               ]}
            ]}
        """.trimIndent()
        val g = OryxJsonDiExtractor.extract(json.toByteArray(), DiagramGeometry.Notation.DMN)
        assertEquals(listOf("svc", "dec_score", "dec_a"), g.shapes.map { it.elementId })
        val svc = g.shapes.first()
        assertEquals(ShapeKind.DMN_DECISION_SERVICE, svc.kind)
        assertEquals(listOf(Point(100.0, 170.0), Point(500.0, 170.0)), svc.divider)
        assertEquals("a decision inside a section keeps its absolute place", 240.0, g.shapes.last().y, 0.0)
    }

    /**
     * Event listeners fell through to the generic box: a 30-px rounded square with the name crammed into
     * it, and a title that told a user listener from a timer from a task by nothing.
     */
    @Test
    fun aCmmnEventListenerIsARingWithItsTypeAndItsNameBelow() {
        val g = XmlDiExtractor.extract(bytes("DEMO-listeners.cmmn"), DiagramGeometry.Notation.CMMN)
        val expected = mapOf(
            "uel_cancel" to (DiaIcon.USER to "User event listener"),
            "tel_timeout" to (DiaIcon.TIMER to "Timer"),
            "vel_flag" to (null to "Variable listener"),
            "el_any" to (null to "Event listener"),
        )
        for ((id, type) in expected) {
            val s = g.shapes.single { it.elementId == id }
            assertEquals(id, ShapeKind.CMMN_EVENT_LISTENER, s.kind)
            assertEquals(id, type.first, s.icon)
            assertEquals(id, type.second, s.typeLabel)
        }
        val svg = DiagramSvgRenderer.render(g)!!
        val cancel = group(svg, "uel_cancel")
        assertTrue(cancel, cancel.contains("""aria-label="Cancel request — User event listener""""))
        assertEquals("a double ring: $cancel", 2, Regex("<circle cx=\"255\" cy=\"140\"").findAll(cancel).count())
        assertFalse("no box", cancel.contains("<rect"))
        assertTrue("its glyph", cancel.contains("""data-icon="user""""))
        // the name goes under the ring (bottom 155), not into it
        val ys = Regex("""<text x="[\d.]+" y="([\d.]+)"""").findAll(cancel).map { it.groupValues[1].toDouble() }.toList()
        assertTrue("the name is drawn: $cancel", ys.isNotEmpty())
        assertTrue("…below the ring: $ys", ys.all { it > 155 })
        assertTrue(group(svg, "tel_timeout").contains("""data-icon="timer""""))
    }

    /** `triggeredByEvent` and `isInterrupting` are BPMN's own words for it; only a stencil was read. */
    @Test
    fun anEventSubProcessFromPlainBpmnIsDashedAndItsNonInterruptingStartToo() {
        val xml = """<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                       xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                       xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC">
              <message id="m" name="DEMO-cancel"/>
              <process id="p">
                <subProcess id="esp" name="On cancel" triggeredByEvent="true">
                  <startEvent id="esp_start" isInterrupting="false"><messageEventDefinition messageRef="m"/></startEvent>
                </subProcess>
                <subProcess id="plain" name="Plain"/>
              </process>
              <bpmndi:BPMNDiagram><bpmndi:BPMNPlane bpmnElement="p">
                <bpmndi:BPMNShape bpmnElement="esp" isExpanded="true"><omgdc:Bounds x="0" y="0" width="300" height="160"/></bpmndi:BPMNShape>
                <bpmndi:BPMNShape bpmnElement="esp_start"><omgdc:Bounds x="40" y="60" width="30" height="30"/></bpmndi:BPMNShape>
                <bpmndi:BPMNShape bpmnElement="plain" isExpanded="true"><omgdc:Bounds x="400" y="0" width="200" height="160"/></bpmndi:BPMNShape>
              </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
            </definitions>""".toByteArray()
        val g = XmlDiExtractor.extract(xml, DiagramGeometry.Notation.BPMN)
        val esp = g.shapes.single { it.elementId == "esp" }
        assertEquals(ShapeKind.EVENT_SUBPROCESS, esp.kind)
        assertEquals("Event sub-process", esp.typeLabel)
        assertEquals(ShapeKind.SUBPROCESS, g.shapes.single { it.elementId == "plain" }.kind)
        assertTrue(DiaMarker.NON_INTERRUPTING in g.shapes.single { it.elementId == "esp_start" }.markers)

        val svg = DiagramSvgRenderer.render(g)!!
        assertTrue("a dashed border", group(svg, "esp").contains("""stroke-dasharray="6 4""""))
        assertTrue("a dashed start", group(svg, "esp_start").contains("""stroke-dasharray="4 3""""))
        assertFalse(group(svg, "plain").contains("stroke-dasharray"))
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
