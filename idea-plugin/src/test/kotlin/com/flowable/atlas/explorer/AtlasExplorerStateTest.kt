package com.flowable.atlas.explorer

import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jdom.Element

/**
 * An explorer tab reopened after a restart comes back on the page it was left on: the route goes into
 * the workspace with the tab and comes back out unchanged. (The JCEF half — the tab navigating to it —
 * has no headless seam; the sandbox covers that.)
 */
class AtlasExplorerStateTest : BasePlatformTestCase() {

    private val provider = AtlasFileEditorProvider()

    fun testTheRouteSurvivesTheWorkspace() {
        val file = myFixture.addFileToProject("atlas-output/demo.explorer.html", "<html></html>").virtualFile
        for (route in listOf("process%3ADEMO-P001", "/checks&f=Missing%20model%20refs")) {
            val element = Element("state")
            provider.writeState(AtlasExplorerState(route), project, element)
            assertEquals(route, (provider.readState(element, project, file) as AtlasExplorerState).route)
        }
    }

    fun testTheDashboardWritesNothing() {
        val element = Element("state")
        provider.writeState(AtlasExplorerState(""), project, element)
        assertTrue("an empty route is the dashboard, and needs no attribute", element.attributes.isEmpty())
    }

    fun testTwoStatesOfTheSameTabMerge() {
        assertTrue(AtlasExplorerState("a").canBeMergedWith(AtlasExplorerState("b"), FileEditorStateLevel.FULL))
    }
}
