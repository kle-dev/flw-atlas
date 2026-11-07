package com.flowable.atlas.render

import com.flowable.atlas.GoldenFiles
import com.flowable.atlas.graph.Atlas
import com.flowable.atlas.graph.GraphDelta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Byte-exact golden gate for [ChangesRenderer]: the `miniproject` fixture against its second version, whose
 * change adds a task with a form of its own and a call to a process nothing defines, renames a form field and
 * fixes a broken script. The golden is what `--changes --stdout` prints, trailing newline included.
 */
class ChangesRendererTest {

    @Test
    fun changesGolden() {
        val base = resource("miniproject")
        val head = miniprojectV2(Files.createTempDirectory("atlas-changes").toFile())
        val delta = GraphDelta.of(Atlas.extract(base), Atlas.extract(head))
        GoldenFiles.assertMatches("miniproject.changes.md", ChangesRenderer.render(delta, "miniproject", against = "../miniproject") + "\n")
    }

    @Test
    fun anUnchangedProjectSaysSoInOneLine() {
        val result = Atlas.extract(resource("miniproject"))
        val text = ChangesRenderer.render(GraphDelta.of(result, result), "miniproject", against = "atlas-base")
        assertEquals("# What changed — `miniproject`\n\nNo model, reference or finding changed since `atlas-base`.", text)
    }

    @Test
    fun aBaselineFromAnotherAtlasIsCalledOutInTheReport() {
        val result = Atlas.extract(resource("miniproject"))
        val text = ChangesRenderer.render(GraphDelta.of(result, result), "miniproject", against = "old.json", baselineAtlas = "0.0.1")
        assertTrue(text, text.contains("written by Atlas 0.0.1"))
    }

    /** A model changed only in a copy the graph does not show names the file, since its elements are not listed. */
    @Test
    fun aChangeToAnotherCopyNamesTheFile() {
        val form = GraphDelta.Model("form:DEMO-form", "DEMO form", "app-bar.zip!f.form")
        val change = GraphDelta.ModelChange(form, emptyList(), emptyList(), emptyList(), emptyList(), elsewhere = listOf("design/app.zip!form-models/f.json"))
        val text = ChangesRenderer.render(
            GraphDelta(emptyList(), emptyList(), listOf(change), emptyList(), emptyList(), emptyList(), emptyList()), "miniproject", against = "base",
        )
        assertTrue(text, text.contains("- `form:DEMO-form` DEMO form — changed in another file: `design/app.zip!form-models/f.json`"))
    }

    private fun resource(name: String) = File(javaClass.classLoader.getResource(name)!!.toURI())

    /**
     * The second version of the fixture: `miniproject` with `miniproject-v2` laid over it. The overlay holds
     * only the files the change touches, so the rest of the fixture can grow without a second copy to keep in
     * step — a change to one of the overlaid files has to be made in both.
     */
    private fun miniprojectV2(into: File): File {
        val dir = File(into, "miniproject")
        resource("miniproject").copyRecursively(dir)
        resource("miniproject-v2").copyRecursively(dir, overwrite = true)
        return dir
    }
}
