package com.flowable.atlas

import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Feature 5: methods referenced from a model expression get a gutter marker; unreferenced ones don't.
 */
class FlowableModelReferenceLineMarkerTest : BasePlatformTestCase() {

    fun testGutterOnlyOnReferencedMethod() {
        myFixture.addFileToProject(
            "models/P.bpmn",
            """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="P">""" +
                """<serviceTask id="t" flowable:expression="${'$'}{orderService.process()}"/></process></definitions>""",
        )
        // Build the index so the marker's cachedOrNull() lookup is populated.
        project.service<FlowableModelIndexService>().index()

        myFixture.addFileToProject(
            "OtherService.java",
            "@Service public class OtherService { public void process() {} }",
        )
        myFixture.configureByText(
            "OrderServiceImpl.java",
            "@Service(\"orderService\") public class OrderServiceImpl { public void process() {} public void unused() {} }",
        )
        myFixture.doHighlighting()

        val referencedGutters = myFixture.findAllGutters().filter { it.tooltipText == "Referenced by Flowable models" }
        // The class (its bean `orderService` is the expression's root) and its process(). Not unused(), and
        // not OtherService.process(): the model calls `process` on `orderService`, which is not that class.
        assertEquals("the bean's class and the method called on it", 2, referencedGutters.size)
    }

    fun testAMethodOfTheSameNameOnAnotherClassIsNotReferenced() {
        myFixture.addFileToProject(
            "models/P.bpmn20.xml",
            """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="P">""" +
                """<serviceTask id="t" flowable:expression="${'$'}{orderService.process()}"/></process></definitions>""",
        )
        myFixture.addFileToProject("OrderService.java", "@Service public class OrderService { public void process() {} }")
        project.service<FlowableModelIndexService>().index()

        // `process` on the `orderService` bean is OrderService's; a plain class — no bean at all — with a
        // method of that name was marked all the same
        myFixture.configureByText("Customer.java", "public class Customer { public void process() {} }")
        myFixture.doHighlighting()

        assertEquals(0, myFixture.findAllGutters().count { it.tooltipText == "Referenced by Flowable models" })
    }
}
