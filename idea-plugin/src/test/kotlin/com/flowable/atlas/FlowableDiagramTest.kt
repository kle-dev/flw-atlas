package com.flowable.atlas

import com.flowable.atlas.usage.FlowableDiagram
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The diagram locator: a Design-export model file resolves to its sibling `.svg`; a model file with
 * none (e.g. a deployment BAR) returns null; the `.svg` extension is matched case-insensitively.
 * DEMO-* placeholder names — this repo is public.
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
}
