package com.flowable.atlas

import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The built index carries the wall-clock time it was scanned, for the Atlas Hub "Last scanned" line. */
class ModelIndexTimestampTest : BasePlatformTestCase() {

    fun testIndexCarriesBuildTimestamp() {
        myFixture.addFileToProject("models/P.bpmn", """<definitions><process id="P" name="P"/></definitions>""")
        val before = System.currentTimeMillis()
        val index = project.service<FlowableModelIndexService>().refresh()
        assertTrue("index should carry a build timestamp", index.builtAtMillis >= before)
    }
}
