package com.flowable.atlas.settings

import com.flowable.atlas.project.AtlasProjectRootService
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * A project settings page says whose values it shows when that is a question: the values are the active
 * Flowable sub-project's, and the sub-project is chosen elsewhere — in the Atlas Hub's header.
 */
class ScopeLineTest : BasePlatformTestCase() {

    fun testASingleProjectRepositoryNeedsNoScopeLine() {
        assertNull(scopeLineText(project))
    }

    fun testAnActiveSubProjectIsNamed() {
        val roots = AtlasProjectRootService.getInstance(project)
        try {
            roots.setActiveSubProject("DEMO-orders")
            val text = scopeLineText(project)!!
            assertTrue(text, text.contains("DEMO-orders"))
            assertTrue(text, text.contains("Atlas Hub"))
        } finally {
            roots.setActiveSubProject("")
        }
    }
}
