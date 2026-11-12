package com.flowable.atlas.navigation

import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Inside a model file, a key gets the same hover card and diagram gutter a key literal in Java has:
 * the file's own key and every process/case cross-reference.
 */
class ModelFileHoverAndGutterTest : BasePlatformTestCase() {

    private fun addTargets() {
        myFixture.addFileToProject(
            "models/DEMO-P100.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="DEMO-P100" name="Order fulfilment"><startEvent id="s"/></process></definitions>""",
        )
        myFixture.addFileToProject("models/customerService.service", """{"key":"customerService","name":"Customer service","type":"database","tableName":"CUSTOMER","operations":[]}""")
        project.service<FlowableModelIndexService>().index()
    }

    fun testHoverOnAnXmlCrossReferenceAndOnTheOwnKey() {
        addTargets()
        myFixture.configureByText(
            "caller.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="CAL<caret>LER" name="Caller"><callActivity id="a" calledElement="DEMO-P100"/></process></definitions>""",
        )
        val own = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val ownDoc = FlowableKeyDocumentationProvider().generateDoc(own, own)
        assertNotNull("the file's own key has a card", ownDoc)
        assertTrue("…naming the type and the name: $ownDoc", ownDoc!!.contains("Process") && ownDoc.contains("Caller"))
        val ref = myFixture.file.findElementAt(myFixture.file.text.indexOf("DEMO-P100") + 2)!!
        val refDoc = FlowableKeyDocumentationProvider().generateDoc(ref, ref)
        assertTrue("a calledElement has the callee's card: $refDoc", refDoc != null && refDoc.contains("Order fulfilment"))
        assertEquals("<b>DEMO-P100</b> — Process · Order fulfilment", FlowableKeyDocumentationProvider().getQuickNavigateInfo(ref, ref))
    }

    fun testHoverOnAJsonCrossReferenceShowsTheBackingTable() {
        addTargets()
        myFixture.configureByText("customer.data", """{"key":"customerDO","name":"Customer","referencedServiceDefinitionModelKey":"customer<caret>Service"}""")
        val leaf = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val doc = FlowableKeyDocumentationProvider().generateDoc(leaf, leaf)
        assertTrue("the service's card, with its table: $doc", doc != null && doc.contains("Customer service") && doc.contains("CUSTOMER"))
        val name = myFixture.file.findElementAt(myFixture.file.text.indexOf("\"Customer\"") + 2)!!
        assertNull("a name is not a key", FlowableKeyDocumentationProvider().generateDoc(name, name))
    }

    fun testTheDiagramGutterSitsOnTheOwnKeyAndOnEveryCallActivity() {
        addTargets()
        myFixture.configureByText(
            "caller.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn">
                 <process id="CALLER" name="Caller">
                   <callActivity id="a" calledElement="DEMO-P100"/>
                   <callActivity id="b" calledElement="DEMO-P999"/>
                   <userTask id="t" name="DEMO-P100"/>
                 </process>
               </definitions>""",
        )
        myFixture.doHighlighting()
        val tips = myFixture.findAllGutters().mapNotNull { it.tooltipText }.filter { it.contains(" diagram: ") }.sorted()
        assertEquals(listOf("Process diagram: CALLER", "Process diagram: DEMO-P100"), tips)
    }
}
