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
import java.util.concurrent.atomic.AtomicBoolean
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

    /**
     * The Hub reacts to a sub-project switch (an invalidation) by asking for the index again. Asked while
     * the old scope's build was still running, it used to join that build — whose result is discarded
     * because the scope moved — and nothing ever built the new scope: *scanning…* until something
     * unrelated asked.
     */
    fun testAnInvalidationDuringABuildStartsAFreshOne() {
        myFixture.addFileToProject("models/P.bpmn", """<definitions><process id="P" name="P"/></definitions>""")
        val service = project.service<FlowableModelIndexService>()
        service.invalidate()
        val updates = countUpdatesWithAnIndex(service)

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        service.afterScanHook = {
            if (first.getAndSet(false)) { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
        }
        try {
            ApplicationManager.getApplication().executeOnPooledThread { service.ensureBuildingAsync() }
            assertTrue("the first build never reached its scan", entered.await(10, TimeUnit.SECONDS))
            service.invalidate()             // the scope moved while that build runs …
            service.ensureBuildingAsync()    // … and the Hub, reacting to it, asks again
            release.countDown()

            pumpUntil { service.cachedOrNull() != null && updates.get() > 0 }
            val index = service.cachedOrNull()
            assertNotNull("a build for the new scope landed", index)
            assertNotNull(index!!.find("P").firstOrNull())
            assertTrue("and it was announced", updates.get() >= 1)
        } finally {
            release.countDown()
            service.afterScanHook = null
        }
    }

    /** A build of a scope that was invalidated under it may fail for files that are gone; recorded, that
     *  failure stopped the current scope from building at all until a Rebuild. */
    fun testAStaleBuildsFailureDoesNotBlockTheNewScope() {
        myFixture.addFileToProject("models/Q.bpmn", """<definitions><process id="Q" name="Q"/></definitions>""")
        val service = project.service<FlowableModelIndexService>()
        service.invalidate()

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        service.afterScanHook = {
            if (first.getAndSet(false)) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                throw IllegalStateException("DEMO stale build failure")
            }
        }
        try {
            // index() joins the build it starts, so this future completes once the build has failed — and
            // the failure is recorded (or not) before a joiner hears of it.
            val stale = ApplicationManager.getApplication().executeOnPooledThread<Throwable?> {
                runCatching { service.index() }.exceptionOrNull()
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            service.invalidate()
            release.countDown()
            assertNotNull("the stale build failed", stale.get(10, TimeUnit.SECONDS))
            assertNull("the stale build's failure is not the current scope's", service.lastFailureOrNull())

            service.ensureBuildingAsync()
            pumpUntil { service.cachedOrNull() != null }
            assertNotNull("the current scope builds", service.cachedOrNull()?.find("Q")?.firstOrNull())
        } finally {
            release.countDown()
            service.afterScanHook = null
        }
    }

    private fun countUpdatesWithAnIndex(service: FlowableModelIndexService): AtomicInteger {
        val updates = AtomicInteger()
        project.messageBus.connect(testRootDisposable).subscribe(
            AtlasEvents.TOPIC,
            object : AtlasEventsListener {
                override fun modelIndexUpdated() { if (service.cachedOrNull() != null) updates.incrementAndGet() }
            },
        )
        return updates
    }

    /** Pump the EDT until [done] — a pooled build publishes through it — for at most ten seconds. */
    private fun pumpUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }
}
