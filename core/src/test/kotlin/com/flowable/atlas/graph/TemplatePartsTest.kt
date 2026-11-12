package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A template's body and attachments travel in files beside the `.tpl`; they belong to the template,
 * loose or inside an archive, and what the body reads reaches the graph.
 */
class TemplatePartsTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        private fun tpl(key: String) = """{"name":"$key mail","key":"$key","templateType":"text","variationContentType":"plainTextTemplate"}"""
        private fun variation(key: String, who: String) =
            """[{"templateDefinitionKey":"$key","parameterValues":{"lang":"en"},
                "variationContent":"<p>Hello ${'$'}{root.$who},</p><p>your request ${'$'}{root.requestId} is approved.</p>"}]"""
        private fun metadata(key: String) = """{"id":"template-$key","name":"letter-$key.docx","type":"template-file","resourceType":"application/msword"}"""

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-template-parts-test").toFile()
            File(dir, "template-DEMO-T1.tpl").writeText(tpl("DEMO-T1"))
            File(dir, "template-DEMO-T1.tplvariation").writeText(variation("DEMO-T1", "firstName"))
            File(dir, "template-DEMO-T1.tplfile-metadata").writeText(metadata("DEMO-T1"))
            // the same three files inside a deployment archive
            ZipOutputStream(File(dir, "app.bar").outputStream()).use { z ->
                fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
                put("template-DEMO-T2.tpl", tpl("DEMO-T2"))
                put("template-DEMO-T2.tplvariation", variation("DEMO-T2", "lastName"))
                put("template-DEMO-T2.tplfile-metadata", metadata("DEMO-T2"))
                put("template-DEMO-T9.tplvariation", variation("DEMO-T9", "nobody"))
            }
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun node(id: String) = ((result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>)
        .single { it["id"] == id }["data"] as Map<String, Any?>

    @Test
    @Suppress("UNCHECKED_CAST")
    fun theBodyAndTheAttachmentsSitOnTheTemplateLooseOrArchived() {
        for (key in listOf("DEMO-T1", "DEMO-T2")) {
            val d = node("template:$key")
            val vs = d["variations"] as List<Map<String, Any?>>
            assertEquals(1, vs.size)
            assertTrue((vs[0]["text"] as String).startsWith("<p>Hello"))
            assertEquals(mapOf("lang" to "en"), vs[0]["parameters"])
            assertTrue((d["content"] as String).contains("approved"))
            assertEquals(listOf("letter-$key.docx"), (d["attachments"] as List<Map<String, Any?>>).map { it["name"] })
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun whatTheBodyReadsReachesTheGraph() {
        val ids = ((result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>).map { it["id"] as String }.toSet()
        assertTrue(ids.toString(), "variable:firstName" in ids && "variable:lastName" in ids && "variable:requestId" in ids)
        val reads = node("variable:requestId")["reads"] as List<Map<String, Any?>>
        assertEquals(setOf("template:DEMO-T1", "template:DEMO-T2"), reads.map { it["model"] }.toSet())
        assertTrue(reads.all { it["via"] == "template" })
        assertTrue("expression:\${root.requestId}" in ids)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aVariationOfAnAbsentTemplateIsSaidNotDropped() {
        val skips = (result["diagnostics"] as List<Map<String, Any?>>).filter { it["kind"] == "skip" }.map { it["message"].toString() }
        assertTrue(skips.toString(), skips.any { it.contains("DEMO-T9") })
    }
}
