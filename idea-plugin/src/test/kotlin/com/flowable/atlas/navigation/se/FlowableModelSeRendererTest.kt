package com.flowable.atlas.navigation.se

import com.flowable.atlas.index.ModelEntry
import com.flowable.atlas.model.ModelType
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.JBList
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.BorderLayout

/**
 * A result row puts what you matched on the left and the file it lives in right-aligned at the far
 * edge, the way the platform's own Search Everywhere tabs do — so the file names line up down the
 * list instead of trailing behind keys of varying length.
 */
class FlowableModelSeRendererTest : BasePlatformTestCase() {

    fun testModelRowShowsKeyLeftAndFileRight() {
        val file = myFixture.addFileToProject("models/demo-invoice.bpmn", "<definitions/>").virtualFile
        val item = FlowableSeItem.Model(
            ModelEntry("DEMO-P001", "Demo Invoice", ModelType.PROCESS, file),
            displayPath = "app.zip → processes/invoice.bpmn",
        )

        assertEquals("DEMO-P001", render(item, BorderLayout.CENTER))
        // The bare file name — not the archive, not the path, not the folder.
        assertEquals("demo-invoice.bpmn", render(item, BorderLayout.EAST))
    }

    fun testTextHitRowShowsTheLineLeftAndFileNameRight() {
        val file = myFixture.addFileToProject("models/demo-other.bpmn", "<definitions/>").virtualFile
        val line = """<userTask id="approve"/>"""
        val item = FlowableSeItem.TextHit(
            file = file,
            displayPath = "app.zip → processes/invoice.bpmn",
            line = 41,
            column = 4,
            lineText = line,
            matchStart = line.indexOf("approve"),
            matchLength = "approve".length,
        )

        // Three fragments (before / highlighted match / after) still read as the one line.
        assertEquals(line, render(item, BorderLayout.CENTER))
        assertEquals("demo-other.bpmn", render(item, BorderLayout.EAST))
    }

    /** The text the renderer put into the [side] component of its BorderLayout. */
    private fun render(item: FlowableSeItem, side: String): String {
        val renderer = FlowableModelSeRenderer { null }
        renderer.getListCellRendererComponent(JBList<Any>(), item, 0, false, false)
        val component = (renderer.layout as BorderLayout).getLayoutComponent(side)
        return (component as SimpleColoredComponent).toString()
    }
}
