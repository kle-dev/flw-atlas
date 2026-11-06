package com.flowable.atlas.intention

import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * What *Open in Atlas Explorer* recognises inside a model file: a cross-reference and the file's own
 * key — and not a name that happens to equal one. (The Java side is covered with the key inspections.)
 * And on the Java side of the models: the name of a class, method or REST handler a model uses opens its
 * page — the class page, the method page, the endpoint page — and a class no model knows offers nothing.
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

    private fun routeAtCaret() = OpenInAtlasExplorerIntention.routeAt(project, myFixture.file, myFixture.caretOffset)

    private fun addJavaUsers() {
        myFixture.addFileToProject(
            "models/DEMO-P200.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="DEMO-P200">""" +
                """<serviceTask id="a" flowable:class="com.example.DemoDelegate"/>""" +
                """<serviceTask id="b" flowable:expression="${'$'}{demoService.place()}"/></process></definitions>""",
        )
        project.service<FlowableModelIndexService>().index()
    }

    fun testAClassAModelNamesOpensTheClassPage() {
        addJavaUsers()
        myFixture.configureByText("DemoDelegate.java", "package com.example; public class Demo<caret>Delegate {}")
        assertEquals(OpenInAtlasExplorerIntention.Target("java%3Acom.example.DemoDelegate", OpenInAtlasExplorerIntention.Page.CLASS), routeAtCaret())
        assertTrue(OpenInAtlasExplorerIntention().isAvailable(project, myFixture.editor, myFixture.file))
    }

    fun testAMethodAModelCallsOpensTheMethodPage() {
        addJavaUsers()
        myFixture.configureByText(
            "DemoService.java",
            """package com.example; @Service("demoService") public class DemoService { public void pl<caret>ace() {} public void unused() {} }""",
        )
        assertEquals("method%3Acom.example.DemoService%23place", routeAtCaret()?.route)
    }

    fun testWhileTheIdeIndexesAJavaDeclarationOffersNothing() {
        addJavaUsers()
        myFixture.configureByText("DemoDelegate.java", "package com.example; public class Demo<caret>Delegate {}")
        // Resolving annotation types and searching for beans throw while indexing; no page is the answer then.
        val route = DumbModeTestUtils.computeInDumbModeSynchronously<OpenInAtlasExplorerIntention.Target?>(project) { routeAtCaret() }
        assertNull(route)
    }

    fun testAClassNoModelKnowsOffersNothing() {
        addJavaUsers()
        myFixture.configureByText("Unrelated.java", "package com.example; public class Unre<caret>lated { public void place() {} }")
        assertNull(routeAtCaret())
        assertFalse(OpenInAtlasExplorerIntention().isAvailable(project, myFixture.editor, myFixture.file))
    }

    fun testARestHandlerOpensItsEndpointPage() {
        addJavaUsers()
        myFixture.configureByText(
            "DemoController.java",
            """@RestController @RequestMapping("/api") class DemoController { @GetMapping("/x") public String x<caret>() { return ""; } }""",
        )
        assertEquals(
            OpenInAtlasExplorerIntention.Target("endpoint%3AGET%20%2Fapi%2Fx", OpenInAtlasExplorerIntention.Page.ENDPOINT),
            routeAtCaret(),
        )
    }
}
