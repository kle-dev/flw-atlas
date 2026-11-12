package com.flowable.atlas

import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.expr.toolwindow.FlowableExpressionPanel
import com.flowable.atlas.expr.toolwindow.FlowableExpressionToolWindowFactory
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import java.util.concurrent.atomic.AtomicInteger

/**
 * A failed build is recorded and not retried by anything event-driven. The Atlas Playground used to
 * refill its scope list on every `modelIndexUpdated` by waiting for the index — which retried the failed
 * build, whose failure published the next event: a full rescan every few hundred milliseconds, each one
 * an IDE error report, for as long as the playground was open.
 */
class ModelIndexFailureLoopTest : BasePlatformTestCase() {

    private val service get() = project.service<FlowableModelIndexService>()

    override fun tearDown() {
        try {
            service.afterScanHook = null
            service.invalidate()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testAnOpenPlaygroundDoesNotRebuildAFailedIndex() {
        myFixture.addFileToProject("models/DEMO-F001.bpmn", """<definitions><process id="DEMO-F001" name="F"/></definitions>""")
        val builds = AtomicInteger()
        service.afterScanHook = { builds.incrementAndGet(); throw IllegalStateException("DEMO build failure") }
        service.invalidate()
        service.ensureBuildingAsync()
        pump(until = { service.lastFailureOrNull() != null })
        assertNotNull("the build failed and the failure is recorded", service.lastFailureOrNull())
        assertEquals(1, builds.get())

        val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        FlowableExpressionToolWindowFactory().createToolWindowContent(project, toolWindow)
        try {
            val panel = toolWindow.contentManager.contents[0].component as FlowableExpressionPanel
            panel.switchDialect(ExpressionDialect.BACKEND)   // processes are a backend scope
            repeat(3) { project.messageBus.syncPublisher(AtlasEvents.TOPIC).modelIndexUpdated() }
            pump(millis = 1_500)   // several of the panel's 300 ms debounce periods

            assertEquals("nothing event-driven retried the failed build", 1, builds.get())
            assertEquals("the scope list holds its placeholder", 1, panel.scopeItemsSnapshot().size)

            // A model change clears the failure (as does Rebuild), and the list fills from the new build.
            service.afterScanHook = null
            service.invalidate()
            pump(until = { panel.scopeItemsSnapshot().any { it.key == "DEMO-F001" } })
            assertTrue(
                "the playground recovers once the index builds: ${panel.scopeItemsSnapshot().map { it.key }}",
                panel.scopeItemsSnapshot().any { it.key == "DEMO-F001" },
            )
        } finally {
            toolWindow.contentManager.removeAllContents(true)
        }
    }

    private fun pump(millis: Long = 10_000, until: () -> Boolean = { false }) {
        val deadline = System.currentTimeMillis() + millis
        while (!until() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
    }
}
