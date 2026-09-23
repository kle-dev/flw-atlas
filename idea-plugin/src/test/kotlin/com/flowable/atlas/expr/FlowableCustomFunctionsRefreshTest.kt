package com.flowable.atlas.expr

import com.flowable.atlas.expr.catalog.CustomFunctionCatalog
import com.flowable.atlas.expr.catalog.FlowableCustomFunctions
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** A function added to `externals.additionalData` is known without restarting the IDE. */
class FlowableCustomFunctionsRefreshTest : BasePlatformTestCase() {

    fun testAChangedFrontendSourceDropsTheCatalog() {
        val functions = FlowableCustomFunctions.getInstance(project)
        val seeded = CustomFunctionCatalog(mapOf("demo" to setOf("fn")), emptySet(), emptySet(), emptyList(), emptyList())
        functions.setForTest(seeded)
        assertSame(seeded, functions.readyOrRequest()!!.catalog)

        myFixture.addFileToProject("frontend/README.md", "not a source")
        assertSame("a non-source file changes nothing", seeded, functions.readyOrRequest()!!.catalog)

        myFixture.addFileToProject("frontend/src/customFunctions.ts", "export const additionalData = {}")
        assertNotSame("the catalog is read again", seeded, functions.readyOrRequest()?.catalog)
    }
}
