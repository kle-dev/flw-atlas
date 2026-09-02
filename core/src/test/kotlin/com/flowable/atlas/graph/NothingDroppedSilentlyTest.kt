package com.flowable.atlas.graph

import com.flowable.atlas.diagram.ModelBytes
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Whatever Atlas does not read leaves a diagnostic behind — and an archive inside an archive is read.
 */
class NothingDroppedSilentlyTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { z ->
                for ((name, bytes) in entries) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            }
            return out.toByteArray()
        }

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-nothing-dropped-test").toFile()
            val process = """<definitions><process id="innerProcess" name="Inner"/></definitions>""".toByteArray()
            val deeper = zip("too/deep.bpmn" to process)
            val inner = zip("processes/inner.bpmn" to process, "deeper.zip" to deeper)
            // A Design export packing one .bar per app, plus a JSON at the root that is no model wrapper
            // and a legacy wrapper whose body is missing.
            File(dir, "export.zip").writeBytes(zip(
                "apps/inner.bar" to inner,
                "manifest.json" to """{"generatedBy":"design"}""".toByteArray(),
                "form-models/orphan.json" to """{"key":"orphanForm","name":"Orphan"}""".toByteArray(),
                "logo.png" to ByteArray(64),
            ))
            // a Helm chart template wears a Flowable template's extension and is not JSON
            File(dir, "chart/templates/_helpers.tpl").apply { parentFile.mkdirs() }
                .writeText("{{- define \"app.name\" -}}{{ .Chart.Name }}{{- end -}}")
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun diagnostics(): List<Map<String, Any?>> = result["diagnostics"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun nodes(): List<Map<String, Any?>> =
        ((result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>)

    @Test
    fun aModelInsideANestedArchiveIsRead() {
        val p = nodes().singleOrNull { it["id"] == "process:innerProcess" }
        assertNotNull("the process inside apps/inner.bar was not read", p)
        assertEquals("export.zip!apps/inner.bar!processes/inner.bpmn", p!!["file"])
    }

    @Test
    fun theNestedLabelResolvesBackToItsBytes() {
        val resolved = ModelBytes.resolve(dir, "export.zip!apps/inner.bar!processes/inner.bpmn")
        assertNotNull(resolved)
        assertEquals("inner.bpmn", resolved!!.second)
        assertTrue(String(resolved.first).contains("innerProcess"))
    }

    @Test
    fun whatIsNotReadIsSaid() {
        val skips = diagnostics().filter { it["kind"] == "skip" }.associate { it["path"].toString() to it["message"].toString() }
        assertTrue("a second nesting level is skipped, loudly: $skips",
            skips["export.zip!apps/inner.bar!deeper.zip"]?.contains("nested two levels") == true)
        assertTrue("a root JSON that is no wrapper is said: $skips",
            skips["export.zip!manifest.json"]?.contains("no model key") == true)
        assertTrue("a wrapper without a body is said: $skips",
            skips["export.zip!form-models/orphan.json"]?.contains("no editorJson") == true)
        assertTrue("a .tpl that is not JSON is a skip, not a parse error: $skips",
            skips["chart/templates/_helpers.tpl"]?.contains("not a JSON document") == true)
        assertTrue("an image inside the archive is nobody's business", skips.keys.none { it.endsWith("logo.png") })
        assertTrue("no parse errors at all", diagnostics().none { it["kind"] == "parse" })
        @Suppress("UNCHECKED_CAST")
        val bindings = ((result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>).filter { it["type"] == "binding" }
        assertTrue("Go-template braces are not frontend bindings", bindings.isEmpty())
    }

    @Test
    fun theSkipsAreParseIssueWarnings() {
        @Suppress("UNCHECKED_CAST")
        val parse = (result["findings"] as List<Map<String, Any?>>).filter { it["check"] == "parseIssues" }
        assertEquals(diagnostics().size, parse.size)
        assertTrue(parse.all { it["severity"] == "warning" })
    }
}
