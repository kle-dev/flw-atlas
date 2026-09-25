package com.flowable.atlas.settings

import com.flowable.atlas.explorer.AtlasArtifact
import com.flowable.atlas.render.ExplorerExtension
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

/**
 * The Generation settings page builds and round-trips what it actually binds — the output folder and
 * the artifact checkboxes — and the artifact selection can never end up empty, whichever way it is
 * written. (An earlier version of this test asserted on the DTO pattern, which had long moved to its
 * own page; it passed because it set the value itself.)
 */
class GenerationConfigurableTest : BasePlatformTestCase() {

    /** The light fixture reuses one project; hand the next test the shipped defaults. */
    override fun tearDown() {
        try {
            val settings = FlowableAtlasProjectSettings.getInstance(project)
            settings.atlasArtifacts = mutableSetOf(AtlasArtifact.EXPLORER_HTML)
            settings.atlasOutputDir = FlowableAtlasProjectSettings.DEFAULT_ATLAS_OUTPUT_DIR
            settings.designTargetFolder = FlowableAtlasProjectSettings.DEFAULT_DESIGN_TARGET_FOLDER
            settings.explorerExtensions = emptySet()
        } finally {
            super.tearDown()
        }
    }

    fun testThePageBuildsAndRoundTripsTheArtifactSelection() {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        settings.atlasArtifacts = mutableSetOf(AtlasArtifact.EXPLORER_HTML, AtlasArtifact.SUMMARY_MD)

        val configurable = GenerationConfigurable(project)
        try {
            assertNotNull("the page must build — a DSL slip only shows when Settings opens", configurable.createComponent())
            assertFalse("freshly built from the settings, nothing is modified", configurable.isModified)

            settings.atlasArtifacts = mutableSetOf(AtlasArtifact.GRAPH_JSON)
            settings.atlasOutputDir = "reports/atlas"
            configurable.reset()
            assertFalse(configurable.isModified)
            configurable.apply()
            assertEquals(setOf(AtlasArtifact.GRAPH_JSON), settings.atlasArtifacts)
            assertEquals("reports/atlas", settings.atlasOutputDir)
        } finally {
            configurable.disposeUIResources()
        }
    }

    /** The pull folder moved here from a page of its own; the page binds it like every other folder. */
    fun testThePulledModelsFolderRoundTrips() {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        val configurable = GenerationConfigurable(project)
        try {
            configurable.createComponent()
            settings.designTargetFolder = "models/pulled"
            configurable.reset()
            assertFalse(configurable.isModified)
            configurable.apply()
            assertEquals("models/pulled", settings.designTargetFolder)
        } finally {
            configurable.disposeUIResources()
        }
    }

    /** One checkbox per explorer extension, bound to the project's choice; nothing is chosen by default. */
    fun testTheExplorerExtensionsRoundTrip() {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        assertTrue("the designer is opt-in", settings.explorerExtensions.isEmpty())
        val configurable = GenerationConfigurable(project)
        try {
            configurable.createComponent()
            settings.explorerExtensions = setOf(ExplorerExtension.ERD)
            configurable.reset()
            assertFalse(configurable.isModified)
            configurable.apply()
            assertEquals(setOf(ExplorerExtension.ERD), settings.explorerExtensions)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testAnEmptyArtifactSelectionFallsBackToTheExplorer() {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        settings.atlasArtifacts = mutableSetOf()
        assertEquals(
            "the setter is where the fallback lives — the page must write through it",
            setOf(AtlasArtifact.EXPLORER_HTML), settings.atlasArtifacts,
        )
    }

    fun testABrowseButtonWritesAProjectRelativePath() {
        val base = Path.of("/work/repo")
        assertEquals("atlas-output", relativeToProject(base, Path.of("/work/repo/atlas-output")))
        assertEquals("src/main/resources/liquibase", relativeToProject(base, Path.of("/work/repo/src/main/resources/liquibase")))
        assertEquals(".", relativeToProject(base, Path.of("/work/repo")))
        assertEquals("/elsewhere/models", relativeToProject(base, Path.of("/elsewhere/models")))
        assertEquals("/work/repo-two/x", relativeToProject(base, Path.of("/work/repo-two/x")))
    }
}
