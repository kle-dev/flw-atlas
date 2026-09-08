package com.flowable.atlas.usage

import com.flowable.atlas.icons.AtlasIcons
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.JBList

/** The chooser behind a gutter mark reads like the Search Everywhere row: type icon, key, file. */
class ModelReferenceNavigatorTest : BasePlatformTestCase() {

    fun testRowsCarryTypeKeyAndFileInCanonicalOrder() {
        val form = myFixture.addFileToProject("models/DEMO-F001.form", """{ "key": "DEMO-F001", "name": "Order form" }""").virtualFile
        val process = myFixture.addFileToProject(
            "models/DEMO-P001.bpmn",
            """<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"><process id="DEMO-P001" name="Order"/></definitions>""",
        ).virtualFile
        project.service<FlowableModelIndexService>().index()

        val rows = ModelReferenceNavigator.rows(project, listOf(form, process, form))
        assertEquals("one row per file and key, duplicates folded", 2, rows.size)
        // Processes come before forms in ModelType's order, whatever order the files arrived in.
        assertEquals(listOf("DEMO-P001", "DEMO-F001"), rows.map { it.key })
        assertEquals(ModelType.PROCESS, rows[0].type)
        assertEquals("Order", rows[0].name)
        assertEquals("DEMO-P001.bpmn", rows[0].where)
    }

    fun testARowShowsTheTypeIconTheKeyAndTheFile() {
        val process = myFixture.addFileToProject(
            "models/DEMO-P002.bpmn",
            """<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"><process id="DEMO-P002" name="Ship"/></definitions>""",
        ).virtualFile
        project.service<FlowableModelIndexService>().index()
        val row = ModelReferenceNavigator.rows(project, listOf(process)).single()

        val renderer = ModelReferenceNavigator.RowRenderer()
        val component = renderer.getListCellRendererComponent(JBList(row), row, 0, false, false) as SimpleColoredComponent
        assertSame(AtlasIcons.forType(ModelType.PROCESS), component.icon)
        val text = component.toString()
        assertTrue(text, text.contains("DEMO-P002") && text.contains("DEMO-P002.bpmn"))
        // The name is what speed search matches, not what the row spends its width on.
        assertFalse(text, text.contains("Ship"))
    }

    fun testAFileTheIndexDoesNotKnowStillGetsARow() {
        val stray = myFixture.addFileToProject("models/stray.bpmn", "<definitions/>").virtualFile
        project.service<FlowableModelIndexService>().index()
        val row = ModelReferenceNavigator.rows(project, listOf(stray)).single()
        assertEquals("stray", row.key)
        assertEquals(ModelType.PROCESS, row.type)
    }
}
