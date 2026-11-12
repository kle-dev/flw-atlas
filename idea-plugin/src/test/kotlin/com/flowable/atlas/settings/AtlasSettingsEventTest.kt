package com.flowable.atlas.settings

import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.events.AtlasEventsListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The regression guard for the defect this whole event contract exists for: a settings page that is
 * applied and tells nobody. `GenerationConfigurable` had no `onApply` hook at all, so the Hub kept
 * listing artifacts from the previous Atlas output folder.
 */
class AtlasSettingsEventTest : BasePlatformTestCase() {

    private class Counter : AtlasEventsListener {
        var applied = 0
        override fun settingsApplied() {
            applied++
        }
    }

    private fun countingApply(build: (Disposable) -> Unit): Int {
        val counter = Counter()
        val parent = Disposer.newDisposable("AtlasSettingsEventTest")
        try {
            project.messageBus.connect(parent).subscribe(AtlasEvents.TOPIC, counter)
            build(parent)
        } finally {
            Disposer.dispose(parent)
        }
        return counter.applied
    }

    fun testApplyingTheGenerationPageNotifiesEveryStatusSurface() {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        val original = settings.atlasOutputDir
        try {
            val fired = countingApply { parent ->
                val page = GenerationConfigurable(project)
                Disposer.register(parent) { page.disposeUIResources() }
                page.createComponent()
                settings.atlasOutputDir = "somewhere-else"
                page.apply()
            }
            assertEquals("the Hub reads atlasOutputDir, so applying this page has to reach it", 1, fired)
        } finally {
            settings.atlasOutputDir = original    // the light fixture reuses one project
        }
    }

    fun testApplyingTheExpressionsPageNotifies() {
        val fired = countingApply { parent ->
            val page = ExpressionsConfigurable(project)
            Disposer.register(parent) { page.disposeUIResources() }
            page.createComponent()
            page.apply()
        }
        assertEquals(1, fired)
    }

    fun testApplyingTheApplicationPageNotifiesEveryOpenProject() {
        val fired = countingApply { parent ->
            val page = FlowableAtlasConfigurable()
            Disposer.register(parent) { page.disposeUIResources() }
            page.createComponent()
            page.apply()
        }
        assertEquals("an IDE-wide change still has to reach each open project's surfaces", 1, fired)
    }
}
