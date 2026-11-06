package com.flowable.atlas.hub

import com.flowable.atlas.explorer.AtlasArtifact
import com.flowable.atlas.explorer.AtlasArtifactFiles
import com.flowable.atlas.explorer.AtlasExplorerFiles
import com.flowable.atlas.explorer.AtlasGenerationRunner
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * Regenerate and "stale" work on the artifacts a project actually selects. A team generating CLAUDE.md and
 * the graph but no page was told "No Atlas Explorer yet" by Regenerate, never saw its CLAUDE.md go stale —
 * and, with an old page left from the default selection, saw a stale line that Regenerate could not clear.
 */
class HubArtifactSetStalenessTest : BasePlatformTestCase() {

    private lateinit var out: Path
    private val settings get() = FlowableAtlasProjectSettings.getInstance(project)

    override fun setUp() {
        super.setUp()
        out = Path.of(project.basePath!!).resolve(settings.atlasOutputDir)
        Files.createDirectories(out)
    }

    override fun tearDown() {
        try {
            settings.atlasArtifacts = mutableSetOf(AtlasArtifact.EXPLORER_HTML)
            out.toFile().deleteRecursively()
            AtlasExplorerFiles.forget()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun artifact(suffix: String, modified: Long): Path {
        val p = out.resolve(AtlasArtifactFiles.projectName(Path.of(project.basePath!!)) + suffix)
        Files.writeString(p, "DEMO")
        Files.setLastModifiedTime(p, FileTime.fromMillis(modified))
        return p
    }

    fun testRegeneratePlansOnTheSelectedSet() {
        val page = listOf(Path.of("DEMO.explorer.html"))
        val claude = listOf(Path.of("DEMO.CLAUDE.md"))
        val agents = setOf(AtlasArtifact.CLAUDE_MD, AtlasArtifact.GRAPH_JSON)
        assertEquals(AtlasGenerationRunner.Plan.ALL, AtlasGenerationRunner.plan(agents, emptyList(), claude))
        assertEquals("nothing generated yet: say so", AtlasGenerationRunner.Plan.NOTHING_THERE, AtlasGenerationRunner.plan(agents, emptyList(), emptyList()))
        assertEquals(AtlasGenerationRunner.Plan.IN_PLACE, AtlasGenerationRunner.plan(setOf(AtlasArtifact.EXPLORER_HTML), page, emptyList()))
        assertEquals(AtlasGenerationRunner.Plan.ALL, AtlasGenerationRunner.plan(setOf(AtlasArtifact.CLAUDE_MD), page, emptyList()))
    }

    fun testTheSelectedFilesOnDiskAreFoundByTheGeneratorsNames() {
        val now = System.currentTimeMillis()
        artifact(".CLAUDE.md", now)
        artifact(".playwright.md", now)
        artifact(".playwright-map.md", now)
        val found = AtlasArtifactFiles.present(Path.of(project.basePath!!), settings.atlasOutputDir,
            setOf(AtlasArtifact.EXPLORER_HTML, AtlasArtifact.CLAUDE_MD, AtlasArtifact.PLAYWRIGHT_MD, AtlasArtifact.GRAPH_JSON))
        assertEquals(listOf(".CLAUDE.md", ".playwright.md", ".playwright-map.md"), found.map { it.fileName.toString().removePrefix(AtlasArtifactFiles.projectName(Path.of(project.basePath!!))) })
    }

    fun testStaleIsJudgedOnWhatRegenerateRefreshes() {
        myFixture.addFileToProject("models/DEMO-P001.bpmn20.xml", """<definitions><process id="DEMO-P001"/></definitions>""")
        project.service<FlowableModelIndexService>().index()
        val hour = 3_600_000L
        val now = System.currentTimeMillis()
        settings.atlasArtifacts = mutableSetOf(AtlasArtifact.CLAUDE_MD, AtlasArtifact.GRAPH_JSON)
        // a page left from the default selection, older than the model
        artifact(".explorer.html", now - hour)

        val beforeRegenerate = HubSnapshot.gather(project) {}
        val stale = HubAttention.of(beforeRegenerate)
        assertEquals("the selection has no page: the line names the artifacts", HubAttention.StaleExplorer(listOf("DEMO-P001"), explorer = false), stale)

        // Regenerate wrote the selected set; the old page keeps its time and must not keep the line
        artifact(".CLAUDE.md", now + hour)
        AtlasExplorerFiles.forget()
        assertNull(HubAttention.of(HubSnapshot.gather(project) {}))
    }

    fun testWithTheExplorerSelectedThePagesStillCount() {
        assertEquals(listOf(1L, 2L), HubSnapshot.staleReference(listOf(1L), listOf(2L), explorerSelected = true))
        assertEquals("without a page in the selection, an old page is not what Regenerate refreshes", listOf(2L), HubSnapshot.staleReference(listOf(1L), listOf(2L), explorerSelected = false))
        assertEquals("…unless nothing selected is there yet", listOf(1L), HubSnapshot.staleReference(listOf(1L), emptyList(), explorerSelected = false))
    }
}
