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

    /**
     * The Playwright guide is one artifact in two files — the guide and the project map it points into — and
     * the CLAUDE.md written beside it names both; without it, CLAUDE.md names neither.
     */
    fun testThePlaywrightGuideIsWrittenWithItsProjectMap() {
        // a user task, so the map has something to hold — with nothing to drive the guide sends nobody there
        File(dir, "models/DEMO-P002.bpmn").writeText("""<definitions><process id="DEMO-P002" name="Demo task">
            <startEvent id="s"/><sequenceFlow id="f" sourceRef="s" targetRef="t"/><userTask id="t" name="Check"/>
            </process></definitions>""")
        val out = dir.toPath().resolve("atlas-output")
        val service = AtlasGeneratorService.getInstance(project)
        val outcome = service.generateAll(dir.toPath(), out, EmptyProgressIndicator(),
            setOf(AtlasArtifact.CLAUDE_MD, AtlasArtifact.PLAYWRIGHT_MD))
        assertTrue(outcome.toString(), outcome is AtlasGeneratorService.Outcome.Success)
        val written = (outcome as AtlasGeneratorService.Outcome.Success).written.map { it.fileName.toString() }.toSet()
        assertEquals(setOf("${dir.name}.CLAUDE.md", "${dir.name}.playwright.md", "${dir.name}.playwright-map.md"), written)
        val guide = Files.readString(out.resolve("${dir.name}.playwright.md"))
        assertTrue("the guide points into its map", guide.contains("${dir.name}.playwright-map.md"))
        // no graph ticked: the hint writes it to the very path every lookup reads
        val graph = "atlas-output/${dir.name}.graph.json"
        assertTrue(guide, guide.contains("--json -o $graph`"))
        assertTrue(guide, guide.lines().filter { it.startsWith("jq '") }.all { it.endsWith(" $graph") })
        val claude = Files.readString(out.resolve("${dir.name}.CLAUDE.md"))
        assertTrue(claude.contains("${dir.name}.playwright.md") && claude.contains("${dir.name}.playwright-map.md"))
        // written by the IDE: no CLI jar the user may not have, no slice the plugin does not offer
        assertFalse(claude, claude.contains("<atlas-cli>.jar") || claude.contains("--slice"))
        assertTrue(claude, claude.contains("*Tools → Flowable Atlas → Generate → Atlas Explorer…*"))

        val alone = dir.toPath().resolve("atlas-output-2")
        service.generateAll(dir.toPath(), alone, EmptyProgressIndicator(), setOf(AtlasArtifact.CLAUDE_MD))
        val unticked = Files.readString(alone.resolve("${dir.name}.CLAUDE.md"))
        assertFalse(unticked.contains("playwright"))
        // it says which checkboxes to tick, by the labels the settings page shows — the two it reads
        for (a in listOf(AtlasArtifact.SUMMARY_MD, AtlasArtifact.OVERVIEW_MD)) {
            assertTrue(unticked, unticked.contains("*${a.label}*"))
        }
        // the graph is the user's choice: unticked, the file neither asks for it nor sends an agent to it
        assertFalse(unticked, unticked.contains(AtlasArtifact.GRAPH_JSON.label) || unticked.contains("graph.json"))
        assertFalse(Files.exists(alone.resolve("${dir.name}.playwright-map.md")))
    }

    /** The summary points only at the artifacts ticked with it: an unticked graph is a file an agent cannot read. */
    fun testTheSummaryNamesOnlyTheArtifactsWrittenBesideIt() {
        val service = AtlasGeneratorService.getInstance(project)
        val alone = dir.toPath().resolve("atlas-output")
        service.generateAll(dir.toPath(), alone, EmptyProgressIndicator(), setOf(AtlasArtifact.SUMMARY_MD))
        val summary = Files.readString(alone.resolve("${dir.name}.summary.md"))
        assertFalse(summary, summary.contains("${dir.name}.graph.json") || summary.contains("${dir.name}.overview.md"))
        assertTrue(summary, summary.contains("*Tools → Flowable Atlas → Generate → Atlas Explorer…*"))

        val withGraph = dir.toPath().resolve("atlas-output-2")
        service.generateAll(dir.toPath(), withGraph, EmptyProgressIndicator(), setOf(AtlasArtifact.SUMMARY_MD, AtlasArtifact.GRAPH_JSON))
        val pointed = Files.readString(withGraph.resolve("${dir.name}.summary.md"))
        assertTrue(pointed, pointed.contains("`${dir.name}.graph.json`"))
        assertFalse(pointed, pointed.contains("${dir.name}.overview.md"))
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

    /**
     * The plugin names its files with the rule the CLI and the artifacts use: a copy of that rule here once
     * wrote `project.*` for a folder named `.DEMO-solution`, beside a CLAUDE.md naming `.DEMO-solution.*`.
     */
    fun testADotPrefixedFolderNamesItsFilesAsItsSiblingsDo() {
        val root = File(dir, ".DEMO-solution")
        File(root, "models").mkdirs()
        File(root, "models/DEMO-P001.bpmn").writeText("""<definitions><process id="DEMO-P001" name="Demo"/></definitions>""")
        val out = dir.toPath().resolve("atlas-output")
        // every file artifact, so whatever CLAUDE.md names has been asked for
        val outcome = AtlasGeneratorService.getInstance(project).generateAll(root.toPath(), out, EmptyProgressIndicator(),
            AtlasArtifact.entries.toSet() - AtlasArtifact.DIAGRAMS_SVG)
        assertTrue(outcome.toString(), outcome is AtlasGeneratorService.Outcome.Success)
        val written = (outcome as AtlasGeneratorService.Outcome.Success).written.map { it.fileName.toString() }
        assertTrue(written.toString(), written.isNotEmpty() && written.all { it.startsWith(".DEMO-solution.") })
        val named = Regex("""\.DEMO-solution\.[A-Za-z-]+\.(md|json|html)""")
            .findAll(Files.readString(out.resolve(".DEMO-solution.CLAUDE.md"))).map { it.value }.toSet()
        assertTrue("CLAUDE.md names its siblings", named.isNotEmpty())
        for (n in named) assertTrue("CLAUDE.md names $n, which was not written", Files.exists(out.resolve(n)))
    }

    /**
     * The output folder's .gitignore names Atlas's artifacts one by one (a catch-all `*` hid the user's own
     * files from git), so an artifact the plugin learns to write must be on that list, or it gets committed.
     */
    fun testTheOutputGitignoreNamesEveryArtifact() {
        val suffixes = com.flowable.atlas.graph.Waivers.ARTIFACT_SUFFIXES
        for (a in AtlasArtifact.entries) assertTrue("${a.suffix} is not in Waivers.ARTIFACT_SUFFIXES", a.suffix in suffixes)
        assertTrue(".${com.flowable.atlas.render.PlaywrightRenderer.MAP_SUFFIX}" in suffixes)
        for (s in suffixes) assertTrue(s, com.flowable.atlas.graph.Waivers.OUTPUT_GITIGNORE.lines().any { it.startsWith("/*$s") })
    }

    /** A regeneration leaves no drawing of a model that is gone — and says which, so the VFS hears of it. */
    fun testARegenerationRemovesTheDrawingsOfModelsThatAreGone() {
        val out = dir.toPath().resolve("atlas-output")
        val stale = out.resolve("${dir.name}.diagrams/DEMO-gone.svg")
        Files.createDirectories(stale.parent)
        Files.writeString(stale, "<svg/>")
        val outcome = AtlasGeneratorService.getInstance(project)
            .generateAll(dir.toPath(), out, EmptyProgressIndicator(), setOf(AtlasArtifact.SUMMARY_MD, AtlasArtifact.DIAGRAMS_SVG))
        assertTrue(outcome.toString(), outcome is AtlasGeneratorService.Outcome.Success)
        assertFalse(Files.exists(stale))
        assertEquals(listOf(stale), (outcome as AtlasGeneratorService.Outcome.Success).removed)
    }

    fun testAPageRemembersTheFolderItWasMadeFrom() {
        val page = dir.toPath().resolve("atlas-output/a.explorer.html")
        assertNull(AtlasExplorerFiles.rootOf(project, page))
        AtlasExplorerFiles.rememberRoot(project, page, dir.toPath())
        assertEquals(dir.toPath().toAbsolutePath().normalize(), AtlasExplorerFiles.rootOf(project, page))
    }
}
