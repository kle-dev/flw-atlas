package com.flowable.atlas.usage

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The diagram gutter marker recognises a model key passed as a **constant or local variable** at a
 * Flowable key call site (`processDefinitionKey(PROCESS_KEY)`), not only as an inline literal — the
 * generated model-constants / "extract the key into a variable" pattern. DEMO-* names — repo public.
 */
class FlowableDiagramLineMarkerTest : BasePlatformTestCase() {

    private val diagramTooltip = "Open the Flowable model diagram"

    private fun addBuilderStubAndProcess() {
        myFixture.addFileToProject(
            "org/flowable/engine/runtime/ProcessInstanceBuilder.java",
            "package org.flowable.engine.runtime; public interface ProcessInstanceBuilder { " +
                "ProcessInstanceBuilder processDefinitionKey(String key); }",
        )
        myFixture.addFileToProject(
            "models/DEMO-P039.bpmn20.xml",
            """<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">""" +
                """<process id="DEMO-P039" name="DEMO Compose email"><startEvent id="s"/></process></definitions>""",
        )
        project.service<FlowableModelIndexService>().index()
    }

    private fun diagramGutters() = myFixture.findAllGutters().filter { it.tooltipText == diagramTooltip }

    fun testLiteralAtKeySiteGetsTheMarker() {
        addBuilderStubAndProcess()
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.engine.runtime.ProcessInstanceBuilder b) { " +
                "b.processDefinitionKey(\"DEMO-P039\"); } }",
        )
        myFixture.doHighlighting()
        assertEquals("the literal at a key site should carry the diagram marker", 1, diagramGutters().size)
    }

    fun testLocalVariableAtKeySiteGetsTheMarker() {
        addBuilderStubAndProcess()
        // The key is extracted into a local — the marker belongs on the call line, where the variable
        // is used as the process key.
        myFixture.configureByText(
            "T.java",
            """
            class T {
                void m(org.flowable.engine.runtime.ProcessInstanceBuilder b) {
                    String processDefinitionKey = "DEMO-P039";
                    b.processDefinitionKey(processDefinition<caret>Key);
                }
            }
            """.trimIndent(),
        )
        myFixture.doHighlighting()
        assertEquals("only the call site is a recognised key position", 1, diagramGutters().size)
        assertEquals(
            "the marker must sit on the call line, not on the declaration",
            1,
            myFixture.findGuttersAtCaret().count { it.tooltipText == diagramTooltip },
        )
    }

    fun testConstantReferenceAtKeySiteGetsTheMarker() {
        addBuilderStubAndProcess()
        myFixture.addFileToProject(
            "demo/ModelConstants.java",
            "package demo; public final class ModelConstants { public static final String COMPOSE = \"DEMO-P039\"; }",
        )
        myFixture.configureByText(
            "T.java",
            "class T { void m(org.flowable.engine.runtime.ProcessInstanceBuilder b) { " +
                "b.processDefinitionKey(demo.ModelConstants.COMPOSE); } }",
        )
        myFixture.doHighlighting()
        // Exactly one: the qualifier (`ModelConstants`) is not itself an argument, so it adds no marker.
        assertEquals("a qualified constant at a key site should carry one diagram marker", 1, diagramGutters().size)
    }

    fun testDecisionTableWithoutLayoutStillOpensItsTable() {
        // A Design decision table has no dmndi layout, so DI rendering yields nothing — the click must
        // still land on something: the table painted from the rules.
        val dmn = myFixture.addFileToProject(
            "models/DEMO-D003.dmn",
            """<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/">
                 <decision id="DEMO-D003" name="Eligibility">
                   <decisionTable id="dt1" hitPolicy="FIRST">
                     <input id="i1" label="Total"><inputExpression id="ie1"><text>order.total</text></inputExpression></input>
                     <output id="o1" label="Approved" name="approved"/>
                     <rule id="r1"><inputEntry id="a"><text>&gt; 100</text></inputEntry>
                       <outputEntry id="b"><text>false</text></outputEntry></rule>
                   </decisionTable>
                 </decision>
               </definitions>""",
        )
        val svg = DiagramSvgCache.getInstance(project).resolveDiagram(dmn.virtualFile, ModelType.DECISION)
        assertNotNull("a decision table must resolve to a rendered diagram", svg)
        val text = String(svg!!.contentsToByteArray(), Charsets.UTF_8)
        assertTrue("expected the painted decision table, got: ${text.take(120)}", text.contains("Hit policy: FIRST"))
        assertTrue(text.contains("order.total"))
    }

    fun testReferenceOutsideAKeySiteGetsNoMarker() {
        addBuilderStubAndProcess()
        myFixture.configureByText(
            "T.java",
            """
            class T {
                void m() {
                    String processDefinitionKey = "DEMO-P039";
                    System.out.println(processDefinitionKey);
                }
            }
            """.trimIndent(),
        )
        myFixture.doHighlighting()
        assertEquals("no Flowable call site, no marker", 0, diagramGutters().size)
    }
}
