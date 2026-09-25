package com.flowable.atlas.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The project's ER diagrams reach the page that carries the designer: found where people keep them, not
 * in build output, each passed through as JSON or as the reason it could not be — so the page can say why
 * a diagram is missing rather than just not listing it.
 */
class ErdDiagramFilesTest {

    private fun project(): File = Files.createTempDirectory("erd-files").toFile().also { it.deleteOnExit() }

    @Test
    fun findsDiagramsAndSkipsBuildOutput() {
        val root = project()
        File(root, "docs").mkdirs()
        File(root, "docs/orders.atlas-erd.json").writeText("""{"format":"atlas-erd","version":1,"name":"Orders","tables":[]}""")
        File(root, "build/tmp").mkdirs()
        File(root, "build/tmp/copy.atlas-erd.json").writeText("""{"format":"atlas-erd","version":1}""")
        File(root, "node_modules/x").mkdirs()
        File(root, "node_modules/x/y.atlas-erd.json").writeText("{}")
        File(root, "docs/broken.atlas-erd.json").writeText("""{"format":"atlas-erd",""")
        File(root, "docs/other.json").writeText("{}")

        val found = ErdDiagramFiles.find(root)
        assertEquals(listOf("docs/broken.atlas-erd.json", "docs/orders.atlas-erd.json"), found.map { it["file"] })
        val broken = found[0]
        assertTrue("a file that is not JSON travels as its error: $broken", (broken["error"] as String).startsWith("not valid JSON"))
        @Suppress("UNCHECKED_CAST")
        val doc = found[1]["doc"] as Map<String, Any?>
        assertEquals("Orders", doc["name"])
    }

    @Test
    fun anOversizedFileIsNotEmbedded() {
        val root = project()
        File(root, "big.atlas-erd.json").writeText(" ".repeat((ErdDiagramFiles.MAX_BYTES + 1).toInt()))
        val found = ErdDiagramFiles.find(root)
        assertEquals(1, found.size)
        assertTrue(found[0]["error"].toString().contains("not embedded"))
        assertEquals(null, found[0]["doc"])
    }

    @Test
    fun aSingleFileOrArchiveHasNoProjectDiagrams() {
        val f = File.createTempFile("model", ".bpmn").also { it.deleteOnExit() }
        assertEquals(emptyList<Any>(), ErdDiagramFiles.find(f))
    }

    /** The diagrams ride along only with the designer — a page without it has no reader for them. */
    @Test
    fun theExplorerEmbedsThemOnlyWithTheDesigner() {
        val root = File(javaClass.classLoader.getResource("miniproject")!!.toURI())
        val result = com.flowable.atlas.graph.Atlas.extract(root)
        assertTrue(!ExplorerHtmlRenderer.render(result, root).contains("\"erdDiagrams\""))
        assertTrue(ExplorerHtmlRenderer.render(result, root, extensions = setOf(ExplorerExtension.ERD)).contains("\"erdDiagrams\":["))
    }
}
