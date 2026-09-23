package com.flowable.atlas.hub

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The Hub's *Recent Models*: model files the editor opened, newest first, named by what the index
 * knows; anything that is not a model is ignored, and the list never grows past its capacity.
 */
class RecentModelsServiceTest : BasePlatformTestCase() {

    fun testOpenedModelsAreListedNewestFirstAndNamedByTheIndex() {
        val process = myFixture.addFileToProject(
            "models/DEMO-P001.bpmn20.xml",
            """<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"><process id="DEMO-P001" name="Order"/></definitions>""",
        ).virtualFile
        val form = myFixture.addFileToProject("models/DEMO-F001.form", """{"key":"DEMO-F001","name":"Order form"}""").virtualFile
        val java = myFixture.addFileToProject("A.java", "class A {}").virtualFile
        project.service<FlowableModelIndexService>().index()
        val service = RecentModelsService.getInstance(project)
        service.clear()

        service.record(process)
        service.record(java)
        service.record(form)
        val recent = service.recent(project.service<FlowableModelIndexService>().index())
        assertEquals(listOf("DEMO-F001", "DEMO-P001"), recent.map { it.key })
        assertEquals(listOf(ModelType.FORM, ModelType.PROCESS), recent.map { it.type })
        assertEquals("Order", recent[1].name)
        assertNotNull("an indexed model carries its entry, for the explorer page", recent[1].entry)

        // re-opening moves to the front, no duplicate
        service.record(process)
        assertEquals(listOf("DEMO-P001", "DEMO-F001"), service.recent(null).map { it.key })
        // the file names the row when the index is absent
        assertEquals(listOf("DEMO-P001", "DEMO-F001"), service.recent(null).map { it.key })
    }

    fun testOneModelCanBeTakenOffTheList() {
        val service = RecentModelsService.getInstance(project)
        service.clear()
        val a = myFixture.addFileToProject("models/DEMO-F101.form", """{"key":"DEMO-F101"}""").virtualFile
        val b = myFixture.addFileToProject("models/DEMO-F102.form", """{"key":"DEMO-F102"}""").virtualFile
        service.record(a); service.record(b)
        service.remove(a)
        assertEquals(listOf("DEMO-F102"), service.recent(null).map { it.key })
    }

    fun testTheListIsCappedAndAPanelShowsIt() {
        val service = RecentModelsService.getInstance(project)
        service.clear()
        for (i in 1..RecentModelsService.CAPACITY + 3) {
            service.record(myFixture.addFileToProject("models/DEMO-F$i.form", """{"key":"DEMO-F$i"}""").virtualFile)
        }
        val recent = service.recent(null)
        assertEquals(RecentModelsService.CAPACITY, recent.size)
        assertEquals("DEMO-F${RecentModelsService.CAPACITY + 3}", recent.first().key)

        project.service<FlowableModelIndexService>().index()
        val panel = AtlasHubPanel(project)
        try {
            panel.refreshForTest()
            val view = panel.viewForTest()
            assertEquals(recent.map { it.key }, view.recentKeys)
            assertTrue("the count is a link with a text: ${view.statusText}", view.statusText.endsWith(" models"))
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
        }
    }
}
