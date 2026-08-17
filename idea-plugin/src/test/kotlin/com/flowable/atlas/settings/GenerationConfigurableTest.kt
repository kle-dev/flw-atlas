package com.flowable.atlas.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The Generation settings page builds and round-trips its bindings. A slip in the Kotlin UI DSL (or a
 * field bound to nothing) only surfaces when the page is opened, which no other test does — this is
 * that opening, plus the reset/apply round-trip for the DTO class-name pattern and its regex rename.
 */
class GenerationConfigurableTest : BasePlatformTestCase() {

    /** The light fixture reuses one project; hand the next test the shipped defaults. */
    override fun tearDown() {
        try {
            val settings = FlowableAtlasProjectSettings.getInstance(project)
            settings.dtoClassNamePattern = FlowableAtlasProjectSettings.DEFAULT_DTO_CLASS_PATTERN
            settings.dtoRenameFind = ""
            settings.dtoRenameReplace = ""
        } finally {
            super.tearDown()
        }
    }

    fun testThePageBuildsAndRoundTripsTheDtoClassNamePattern() {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        settings.dtoClassNamePattern = "{app}{name}Bean"
        settings.dtoRenameFind = "^DEMO"
        settings.dtoRenameReplace = "Demo"

        val configurable = GenerationConfigurable(project)
        try {
            assertNotNull("the page must build — a DSL slip only shows when Settings opens", configurable.createComponent())
            assertFalse("freshly built from the settings, nothing is modified", configurable.isModified)

            // Changed behind the panel's back → reset pulls the new values in, apply writes them back.
            settings.dtoClassNamePattern = "Demo{name}Dto"
            configurable.reset()
            assertFalse(configurable.isModified)
            configurable.apply()
            assertEquals("Demo{name}Dto", settings.dtoClassNamePattern)
            assertEquals("^DEMO", settings.dtoRenameFind)
            assertEquals("Demo", settings.dtoRenameReplace)
        } finally {
            configurable.disposeUIResources()
        }
    }
}
