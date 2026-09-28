package com.flowable.atlas.explorer

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

    /** The ER diagram designer is an artifact of its own, linked to the explorer written beside it. */
    fun testTheErPageIsWrittenBesideTheExplorer() {
        val out = dir.toPath().resolve("atlas-output")
        val outcome = AtlasGeneratorService.getInstance(project)
            .generateAll(dir.toPath(), out, EmptyProgressIndicator(), setOf(AtlasArtifact.EXPLORER_HTML, AtlasArtifact.ERD_HTML))
        assertTrue(outcome.toString(), outcome is AtlasGeneratorService.Outcome.Success)
        val erd = Files.readString(out.resolve("${dir.name}.erd.html"))
        assertTrue("the designer's page", erd.contains("window.ATLAS_ERD="))
        assertTrue("linked to the explorer beside it", erd.contains("\"explorer\":\"${dir.name}.explorer.html\""))
        assertFalse("the explorer carries none of it", Files.readString(out.resolve("${dir.name}.explorer.html")).contains("ATLAS_ERD"))
    }

    /** An ER page's own Regenerate rewrites it as an ER page, not as an explorer under the wrong name. */
    fun testRegeneratingAnErPageKeepsItOne() {
        val out = Files.createDirectories(dir.toPath().resolve("atlas-output"))
        val page = out.resolve("a.erd.html")
        val outcome = AtlasGeneratorService.getInstance(project).generateExplorers(dir.toPath(), listOf(page), EmptyProgressIndicator())
        assertTrue(outcome.toString(), outcome is AtlasGeneratorService.Outcome.Success)
        val html = Files.readString(page)
        assertTrue(html.contains("window.ATLAS_ERD="))
        assertFalse("no explorer beside it, so no link", html.contains("a.explorer.html"))
        assertTrue(AtlasArtifact.isPage("x.erd.html") && AtlasArtifact.isPage("x.explorer.html") && !AtlasArtifact.isPage("x.html"))
    }

    fun testAPageRemembersTheFolderItWasMadeFrom() {
        val page = dir.toPath().resolve("atlas-output/a.explorer.html")
        assertNull(AtlasExplorerFiles.rootOf(project, page))
        AtlasExplorerFiles.rememberRoot(project, page, dir.toPath())
        assertEquals(dir.toPath().toAbsolutePath().normalize(), AtlasExplorerFiles.rootOf(project, page))
    }
}
