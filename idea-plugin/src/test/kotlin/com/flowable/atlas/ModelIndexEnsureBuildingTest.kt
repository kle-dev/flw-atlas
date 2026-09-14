package com.flowable.atlas

import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.events.AtlasEventsListener
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `ensureBuilding()` is the one call every read-context consumer makes on a cold index: any number
 * of callers share one background build, and the index arrives without anyone waiting on it. In a
 * light test `ensureBuilding()` builds inline (so inspection tests stay deterministic), which is why
 * this test calls the background half directly.
 */
class ModelIndexEnsureBuildingTest : BasePlatformTestCase() {

    fun testManyCallersShareOneBackgroundBuild() {
        myFixture.addFileToProject("models/P.bpmn", """<definitions><process id="P" name="P"/></definitions>""")
        val service = project.service<FlowableModelIndexService>()
        service.invalidate()
        assertNull("a cold index must not exist before anybody asked", service.cachedOrNull())

        val updates = AtomicInteger()
        project.messageBus.connect(testRootDisposable).subscribe(
            AtlasEvents.TOPIC,
            object : AtlasEventsListener {
                override fun modelIndexUpdated() { if (service.cachedOrNull() != null) updates.incrementAndGet() }
            },
        )

        val started = CountDownLatch(5)
        repeat(5) {
            ApplicationManager.getApplication().executeOnPooledThread {
                service.ensureBuildingAsync(); started.countDown()
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        // ensureBuilding() returns at once; the build lands on a pooled thread, the publish may
        // need the EDT's queue pumped.
        //
        // Wait for the *publish*, not just for the cache. The build sets the cache on its pooled thread
        // and publishes on the EDT, so waiting on the cache alone can fall through while the event is
        // still queued — on a loaded CI runner that is the difference between "one update" and "none",
        // and it failed exactly that way once.
        val deadline = System.currentTimeMillis() + 10_000
        while ((service.cachedOrNull() == null || updates.get() == 0) && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        // And drain once more, so a second publish — the thing "exactly one" is here to catch — has
        // arrived by the time it is counted.
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val index = service.cachedOrNull()
        assertNotNull("the background build never landed", index)
        assertNotNull("the build indexed the model", index!!.find("P").firstOrNull())
        assertEquals("five askers, one build, one modelIndexUpdated with a cached index", 1, updates.get())
    }
}
