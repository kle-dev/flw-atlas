package com.flowable.atlas.intention

import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * What *Open in Atlas Explorer* recognises inside a model file: a cross-reference and the file's own
 * key — and not a name that happens to equal one. (The Java side is covered with the key inspections.)
 */
class OpenInAtlasExplorerEntryAtTest : BasePlatformTestCase() {

    private fun addTargets() {
        myFixture.addFileToProject(
            "models/DEMO-P100.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="DEMO-P100" name="Order fulfilment"/></definitions>""",
        )
        myFixture.addFileToProject("models/customerService.service", """{"key":"customerService","name":"Customer service","operations":[]}""")
        project.service<FlowableModelIndexService>().index()
    }

    private fun entryAtCaret() = OpenInAtlasExplorerIntention.entryAt(project, myFixture.file, myFixture.caretOffset)

    fun testACrossReferenceAndTheOwnKeyInXml() {
        addTargets()
        myFixture.configureByText(
            "caller.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="CALLER" name="Caller"><callActivity id="a" calledElement="DEMO-P<caret>100"/></process></definitions>""",
        )
        assertEquals("DEMO-P100", entryAtCaret()?.key)
        assertTrue("the intention is offered in a model file", OpenInAtlasExplorerIntention().isAvailable(project, myFixture.editor, myFixture.file))
        myFixture.configureByText(
            "caller2.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="CAL<caret>LER" name="Caller"/></definitions>""",
        )
        assertEquals("the file's own key opens its own page", "CALLER", entryAtCaret()?.key)
    }

    fun testAJsonReferenceButNotAName() {
        addTargets()
        myFixture.configureByText("customer.data", """{"key":"customerDO","name":"Customer","referencedServiceDefinitionModelKey":"customer<caret>Service"}""")
        assertEquals("customerService", entryAtCaret()?.key)
        myFixture.configureByText("customer2.data", """{"key":"customerDO","name":"customer<caret>Service"}""")
        assertNull(entryAtCaret())
        assertFalse(OpenInAtlasExplorerIntention().isAvailable(project, myFixture.editor, myFixture.file))
    }
}
