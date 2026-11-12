package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A REST button's *Store response attributes* land under the button's own `value` binding — the
 * platform's button calls back onto its own path. Under `{{$temp.x}}` that is form-local and no
 * variable; under `{{customer}}` it is a field of `customer`, whose write the binding already records.
 * Only a button with no binding writes the mapping names into the payload as variables.
 */
class RestButtonResponseTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-rest-button-test").toFile()
            File(dir, "f.form").writeText(
                """{"metadata":{"key":"DEMO-F001","name":"F","modelType":"form"},"rows":[[
                    {"id":"rest1","type":"restButton","value":"{{${'$'}temp.info}}","extraSettings":{"method":"get","url":"/x",
                      "responsePayloadMapping":[{"name":"deploymentId","expression":"{{${'$'}response.deploymentId}}"}]}},
                    {"id":"rest2","type":"restButton","value":"{{customer}}","extraSettings":{"method":"get","url":"/y",
                      "responsePayloadMapping":[{"name":"email","expression":"{{${'$'}response.email}}"}]}},
                    {"id":"rest3","type":"restButton","extraSettings":{"method":"get","url":"/z",
                      "responsePayloadMapping":[{"name":"caseId","expression":"{{${'$'}response.id}}"}]}},
                    {"id":"shown","type":"text","value":"{{${'$'}temp.info.deploymentId}}"}
                  ]]}""")
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun nodes() = (result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>
    private fun ids() = nodes().map { it["id"] as String }.toSet()

    @Test
    fun aMappingStoredUnderTheButtonsBindingIsNoVariable() {
        val ids = ids()
        assertFalse("form-local under \$temp", "variable:deploymentId" in ids)
        assertFalse("a field of customer, not a variable", "variable:email" in ids)
        assertTrue("the binding's root is the write", "variable:customer" in ids)
        @Suppress("UNCHECKED_CAST")
        val writes = (nodes().single { it["id"] == "variable:customer" }["data"] as Map<String, Any?>)["writes"] as List<Map<String, Any?>>
        assertEquals(listOf("restButton"), writes.map { it["via"] })
    }

    @Test
    fun aButtonWithoutABindingWritesThePayload() {
        assertTrue("variable:caseId" in ids())
        @Suppress("UNCHECKED_CAST")
        val writes = (nodes().single { it["id"] == "variable:caseId" }["data"] as Map<String, Any?>)["writes"] as List<Map<String, Any?>>
        assertEquals(listOf("responsePayloadMapping"), writes.map { it["via"] })
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun thePayloadTableShowsWhereTheResponseLands() {
        val form = (result["forms"] as List<Map<String, Any?>>).single()
        val io = (form["ioParameters"] as List<Map<String, Any?>>)
        val rest1 = io.single { it["element"] == "rest1" }
        assertEquals("\$temp.info.deploymentId", rest1["target"])
        assertEquals("\$temp.info", rest1["storedUnder"])
        assertEquals("caseId", io.single { it["element"] == "rest3" }["target"])
    }
}
