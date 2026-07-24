package com.flowable.atlas.render

import com.flowable.atlas.graph.Atlas
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * End-to-end guard for the archive-diagram fix: a diagram-bearing model packaged inside a `.zip`/`.bar`
 * gets a `"<archive>!<entry>"` graph `file` label (see `Extractor`). Before the fix, [ExplorerHtmlRenderer]
 * rebuilt that via `File(root, label)`, which cannot read a zip entry, so archived processes/cases had no
 * diagram in the generated explorer. This runs the real pipeline (extract → render) over an archive and
 * asserts the inline diagram is embedded.
 */
class ExplorerArchiveDiagramTest {

    private val diagramRes: File by lazy { File(javaClass.classLoader.getResource("diagram")!!.toURI()) }

    @Test
    fun archivedProcessGetsInlineDiagram() {
        val tmp = Files.createTempDirectory("atlas-explorer-archive").toFile()
        try {
            val bpmn = File(diagramRes, "DEMO-onboarding.bpmn20.xml").readBytes()
            ZipOutputStream(File(tmp, "app.zip").outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("DEMO-onboarding.bpmn20.xml"))
                zip.write(bpmn)
                zip.closeEntry()
            }

            val result = Atlas.extract(tmp)
            val html = ExplorerHtmlRenderer.render(result, tmp, version = "test")

            assertTrue("expected the archived process in the graph", html.contains("DEMO-onboarding"))
            assertTrue("expected an inline diagram attached to the archived model", html.contains("\"diagram\""))
        } finally {
            tmp.deleteRecursively()
        }
    }
}
