package com.flowable.atlas.settings

import com.intellij.openapi.options.Configurable
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Opens every Atlas settings page the way the dialog does — build, reset, ask whether it is modified —
 * one after another in the order the tree shows them.
 *
 * Exists because a page that throws while being built does not fail loudly in the Settings dialog: it
 * shows a spinner that never resolves, and the neighbouring pages look broken too. Catching that here
 * costs one test; catching it by hand costs opening five pages after every change.
 */
class AtlasSettingsPagesBuildTest : BasePlatformTestCase() {

    /**
     * `isModified()` is polled by the Settings dialog, so anything it touches must not repaint — a UI
     * mutation on that path feeds the poll loop and the page shows a spinner that never resolves.
     * Calling it repeatedly here is the cheap way to catch that: an implementation that mutates would
     * keep flip-flopping instead of settling.
     */
    fun testAskingWhetherAPageIsModifiedIsStableAndSideEffectFree() {
        val pages: List<Configurable> = listOf(
            EnvironmentsConfigurable(project),
            ExpressionsConfigurable(project),
            GenerationConfigurable(project),
            GenerationConstantsConfigurable(project),
            GenerationLiquibaseConfigurable(project),
            GenerationDtoConfigurable(project),
        )
        try {
            pages.forEach { page ->
                page.createComponent()
                page.reset()
                repeat(20) {
                    assertFalse(
                        "${page.javaClass.simpleName} reports itself modified while nobody is editing it",
                        page.isModified,
                    )
                }
            }
        } finally {
            pages.forEach { it.disposeUIResources() }
        }
    }

    fun testEveryAtlasSettingsPageBuildsResetsAndReportsItsState() {
        val pages: List<Configurable> = listOf(
            FlowableAtlasConfigurable(),
            EnvironmentsConfigurable(project),
            ExpressionsConfigurable(project),
            GenerationConfigurable(project),
            GenerationConstantsConfigurable(project),
            GenerationLiquibaseConfigurable(project),
            GenerationDtoConfigurable(project),
        )
        try {
            pages.forEach { page ->
                assertNotNull("${page.javaClass.simpleName} built no component", page.createComponent())
                page.reset()
                assertFalse(
                    "${page.javaClass.simpleName} reports itself modified right after reset, so Apply " +
                        "would light up on a page nobody touched",
                    page.isModified,
                )
            }
        } finally {
            pages.forEach { it.disposeUIResources() }
        }
    }
}
