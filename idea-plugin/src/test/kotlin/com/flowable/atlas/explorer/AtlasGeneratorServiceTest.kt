package com.flowable.atlas.explorer

import com.flowable.atlas.render.ExplorerExtension
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

/**
 * Generation writes only what a finished run produced: a Cancel leaves the previous artifacts as they
 * were, and several pages of one report share one analysis. DEMO-* names: the repo is public.
 */
class AtlasGeneratorServiceTest : BasePlatformTestCase() {

    private lateinit var dir: File

    override fun setUp() {
        super.setUp()
        dir = FileUtil.createTempDirectory("atlas-generate", null)
        File(dir, "models").mkdirs()
        File(dir, "models/DEMO-P001.bpmn").writeText("""<definitions><process id="DEMO-P001" name="Demo"/></definitions>""")
    }

    override fun tearDown() {
        try { FileUtil.delete(dir) } catch (e: Throwable) { addSuppressedException(e) } finally { super.tearDown() }
    }

    fun testACancelledRunWritesNothing() {
        val out = dir.toPath().resolve("atlas-output")
        val indicator = EmptyProgressIndicator().apply { cancel() }
        try {
            AtlasGeneratorService.getInstance(project).generateAll(dir.toPath(), out, indicator, setOf(AtlasArtifact.SUMMARY_MD))
            fail("a cancelled run must not finish")
        } catch (expected: ProcessCanceledException) {
        }
        assertFalse(Files.exists(out.resolve("${dir.name}.summary.md")))
    }

    fun testPagesOfOneFolderAreWrittenFromOneRun() {
        val out = Files.createDirectories(dir.toPath().resolve("atlas-output"))
        val pages = listOf(out.resolve("a.explorer.html"), out.resolve("b.explorer.html"))
        val outcome = AtlasGeneratorService.getInstance(project).generateExplorers(dir.toPath(), pages, EmptyProgressIndicator())
        assertTrue(outcome.toString(), outcome is AtlasGeneratorService.Outcome.Success)
        assertEquals(Files.readString(pages[0]), Files.readString(pages[1]))
        assertTrue(Files.readString(pages[0]).contains("DEMO-P001"))
    }

    /** The designer is in the page only when the project chose it — in both ways a page is generated. */
    fun testTheExplorerCarriesTheExtensionsTheProjectChose() {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        val out = Files.createDirectories(dir.toPath().resolve("atlas-output"))
        val page = out.resolve("a.explorer.html")
        val gen = AtlasGeneratorService.getInstance(project)
        try {
            gen.generateExplorers(dir.toPath(), listOf(page), EmptyProgressIndicator())
            assertFalse("not chosen, not there", Files.readString(page).contains("ATLAS_EXT.erd="))
            settings.explorerExtensions = setOf(ExplorerExtension.ERD)
            gen.generateExplorers(dir.toPath(), listOf(page), EmptyProgressIndicator())
            assertTrue("chosen, carried", Files.readString(page).contains("ATLAS_EXT.erd="))
            gen.generateAll(dir.toPath(), out, EmptyProgressIndicator(), setOf(AtlasArtifact.EXPLORER_HTML))
            assertTrue("…by the full generator too", Files.readString(out.resolve("${dir.name}.explorer.html")).contains("ATLAS_EXT.erd="))
        } finally {
            settings.explorerExtensions = emptySet()
        }
    }

    fun testAPageRemembersTheFolderItWasMadeFrom() {
        val page = dir.toPath().resolve("atlas-output/a.explorer.html")
        assertNull(AtlasExplorerFiles.rootOf(project, page))
        AtlasExplorerFiles.rememberRoot(project, page, dir.toPath())
        assertEquals(dir.toPath().toAbsolutePath().normalize(), AtlasExplorerFiles.rootOf(project, page))
    }
}
