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

        myFixture.configureByText(
            "OrderServiceImpl.java",
            "public class OrderServiceImpl { public void process() {} public void unused() {} }",
        )
        myFixture.doHighlighting()

        val referencedGutters = myFixture.findAllGutters().filter { it.tooltipText == "Referenced by Flowable models" }
        // Exactly one: the method process(). The class OrderServiceImpl decapitalizes to
        // "orderServiceImpl" (not the referenced bean "orderService"), and unused() is not referenced.
        assertEquals("only the referenced method should carry a marker", 1, referencedGutters.size)
    }
}
