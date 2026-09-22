package com.flowable.atlas.structure

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * A form's components nest as in Design, a process's elements by name; other JSON and XML keep the
 * platform's outline. DEMO-* names: the repo is public.
 */
class ModelStructureViewProviderTest : BasePlatformTestCase() {

    private fun builder(): StructureViewBuilder? {
        val file = myFixture.file.virtualFile
        return StructureViewBuilder.getProvider().getStructureViewBuilder(file.fileType, file, project)
    }

    private fun root(): StructureViewTreeElement =
        (builder() as TreeBasedStructureViewBuilder).createStructureViewModel(myFixture.editor).root

    /** `text (location)`, children indented. */
    private fun dump(e: TreeElement, depth: Int = 0): String {
        val p = e.presentation
        val line = "  ".repeat(depth) + p.presentableText + (p.locationString?.let { " ($it)" } ?: "")
        return (listOf(line) + e.children.map { dump(it, depth + 1) }).joinToString("\n")
    }

    fun testAFormNestsItsComponents() {
        myFixture.configureByText(
            "DEMO-F001.form",
            """
            {"metadata":{"key":"DEMO-F001","name":"Onboarding"},"rows":[
              {"cols":[{"id":"firstName","type":"text","label":"First name"},{"id":"country","type":"select"}]},
              {"cols":[{"id":"details","type":"panel","label":"Details","extraSettings":{"layoutDefinition":{"rows":[
                {"cols":[{"id":"notes","type":"textarea","i18n":{"de_de":{"label":"Notizen"}}}]}]}}}]},
              {"cols":[{"id":"tabs1","type":"tabs","extraSettings":{"sections":[
                {"label":"Customer","extraSettings":{"layoutDefinition":{"rows":[{"cols":[{"id":"customerName","type":"text"}]}]}}}]}}]},
              {"cols":[{"id":"check","type":"restButton","extraSettings":{"text":"Check"}}]}
            ]}
            """.trimIndent(),
        )
        assertEquals(
            """
            Onboarding
              First name (firstName)
              country
              Details (details)
                Notizen (notes)
              tabs1
                Customer
                  customerName
              Check (check)
            """.trimIndent(),
            dump(root()),
        )
    }

    fun testAComponentNavigatesToItsObject() {
        myFixture.configureByText(
            "DEMO-F002.form",
            """{"rows":[{"cols":[{"id":"amount","type":"number","label":"Amount"}]}]}""",
        )
        val node = root().children.single() as StructureViewTreeElement
        val obj = node.value as JsonObject
        assertEquals("amount", (obj.findProperty("id")!!.value as JsonStringLiteral).value)
    }

    fun testAProcessListsItsElementsNotItsDiagram() {
        myFixture.configureByText(
            "DEMO-P001.bpmn20.xml",
            """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI">
              <process id="DEMO-P001" name="Onboarding">
                <startEvent id="start"/>
                <userTask id="review" name="Review"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
                <subProcess id="sub"><serviceTask id="call" name="Call"/></subProcess>
              </process>
              <bpmndi:BPMNDiagram id="d"><bpmndi:BPMNPlane id="pl" bpmnElement="DEMO-P001"/></bpmndi:BPMNDiagram>
            </definitions>
            """.trimIndent(),
        )
        assertEquals(
            """
            definitions
              Onboarding (process)
                start (startEvent)
                Review (userTask)
                sub (subProcess)
                  Call (serviceTask)
            """.trimIndent(),
            dump(root()),
        )
        assertTrue(root().children.first().let { (it as StructureViewTreeElement).value } is XmlTag)
    }

    fun testOtherFilesKeepThePlatformOutline() {
        myFixture.configureByText("package.json", """{"name":"x"}""")
        val b = builder()
        assertNotNull("plain JSON still has an outline", b)
        assertFalse(b!!.javaClass.name.contains("ModelStructureViewProvider"))
        myFixture.configureByText("DEMO-A001.action", """{"key":"DEMO-A001"}""")
        assertFalse(builder()?.javaClass?.name.orEmpty().contains("ModelStructureViewProvider"))
    }
}
