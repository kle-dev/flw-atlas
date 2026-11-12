package com.flowable.atlas.render

import com.flowable.atlas.graph.Atlas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The one naming rule every artifact, the CLI and the plugin share. It used to exist three times — the
 * CLI's port of `os.path.splitext`, the plugin's `substringBeforeLast('.')` and this one — and the copies
 * disagreed on a dot-prefixed folder, so the plugin wrote `project.*` beside a CLAUDE.md that named
 * `.DEMO-solution.*`. The display name had a fourth copy, un-normalized, which headed a project analysed
 * as `.` with `.` and scoped every such project's browser storage to the same key.
 */
class FmtTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun theArtifactNameIsTheFoldersNameWithoutItsLastExtension() {
        val root = tmp.root
        val cases = linkedMapOf(
            ".DEMO-x" to ".DEMO-x",          // a leading dot is not an extension
            "DEMO.solution" to "DEMO",
            "a.b.c" to "a.b",
            "a" to "a",
            "x/" to "x",
            "..x" to "..x",
            "." to root.name,                // run from inside the project
            "sub/.." to root.name,
        )
        for ((given, expected) in cases) {
            assertEquals("artifactName($given)", expected, Fmt.artifactName(File(root, given)))
        }
        assertEquals("a relative `.` is the working directory's name",
            File(".").absoluteFile.normalize().name, Fmt.artifactName(File(".")))
    }

    @Test
    fun theProjectNameKeepsTheExtensionButNeverTheDots() {
        assertEquals("app.zip", Fmt.projectName(File(tmp.root, "app.zip")))
        assertEquals(tmp.root.name, Fmt.projectName(File(tmp.root, ".")))
        assertEquals(tmp.root.name, Fmt.projectName(File(tmp.root.path + "/")))
    }

    /** Same name, different place: different projects, so their pages must not share a browser store. */
    @Test
    fun theProjectKeyTellsApartTwoProjectsOfOneName() {
        val a = File(tmp.newFolder("one"), "DEMO-app").apply { mkdirs() }
        val b = File(tmp.newFolder("two"), "DEMO-app").apply { mkdirs() }
        assertEquals(Fmt.projectName(a), Fmt.projectName(b))
        assertNotEquals(Fmt.projectKey(a), Fmt.projectKey(b))
        assertEquals("`.` from inside is the same project", Fmt.projectKey(a), Fmt.projectKey(File(a, ".")))
        assertTrue("a short hex hash, never the path", Fmt.projectKey(a).matches(Regex("[0-9a-f]{16}")))
    }

    /** Every artifact that shows the project's name shows the directory's, whichever way it was given. */
    @Test
    fun aProjectGivenAsDotIsHeadedByItsDirectoryInEveryArtifact() {
        val fixture = File(javaClass.classLoader.getResource("miniproject")!!.toURI())
        val project = File(tmp.root, "DEMO-app").also { fixture.copyRecursively(it) }
        val dotted = File(project, ".")
        val result = Atlas.extract(dotted)
        assertTrue(SummaryRenderer.render(result, dotted).lineSequence().first().contains("`DEMO-app`"))
        assertTrue(OverviewRenderer.render(result, dotted).lineSequence().first().contains("`DEMO-app`"))
        val key = Fmt.projectKey(project)
        for ((page, html) in listOf(
            "explorer" to ExplorerHtmlRenderer.render(result, dotted, compressAbove = Int.MAX_VALUE),
            "ER page" to ErdHtmlRenderer.render(result, dotted),
        )) {
            assertTrue("the $page is named by the directory", html.contains("\"project\":\"DEMO-app\""))
            assertTrue("the $page is not named `.`", !html.contains("\"project\":\".\""))
            assertTrue("the $page carries the storage key", html.contains("\"projectKey\":\"$key\""))
        }
    }
}
