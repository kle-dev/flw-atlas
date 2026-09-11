package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A marker belongs to the model whose element holds it, is named by where it sits, and is one finding. */
class MarkerAttributionTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-marker-test").toFile()
            File(dir, "two.bpmn").writeText(
                """<definitions xmlns:flowable="http://flowable.org/bpmn">
                     <process id="first"><documentation>fine</documentation><userTask id="a"/></process>
                     <process id="second"><documentation>TODO wire the escalation</documentation><userTask id="b"/></process>
                   </definitions>""")
            val form = """{"metadata":{"key":"minified","name":"Minified","modelType":"form"},"rows":[{"cols":[{"id":"x","type":"text","label":"TODO"}]}]}"""
            File(dir, "minified.form").writeText(form)
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("minified.form")); z.write(form.toByteArray()); z.closeEntry() }
            File(dir, "export.zip").writeBytes(out.toByteArray())
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun markers() = (result["findings"] as List<Map<String, Any?>>).filter { it["check"] == "leftoverMarkers" }

    @Test
    fun aMarkerBelongsToTheProcessWhoseElementHoldsIt() {
        val todo = markers().single { it["file"] == "two.bpmn" }
        assertEquals("process:second", todo["node"])
        assertEquals("wire the escalation", todo["subject"])
    }

    @Test
    fun aTextlessMarkerInAMinifiedModelIsNamedByItsPathAndReportedOnce() {
        val inForm = markers().filter { it["node"] == "form:minified" }
        assertEquals("loose + archived copy = one finding: $inForm", 1, inForm.size)
        assertEquals("rows[0].cols[0].label", inForm.single()["subject"])
    }
}
