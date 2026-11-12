package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A JSON model that starts with a UTF-8 byte-order mark is a model, not a parse failure. */
class BomTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-bom-test").toFile()
            File(dir, "bom.form").writeText(
                "﻿" + """{"metadata":{"key":"bomForm","name":"BOM form","modelType":"form"},"rows":[]}""")
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aBomIsNotAParseFailure() {
        val ids = ((result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>).map { it["id"] }
        assertTrue("the model is in the graph", "form:bomForm" in ids)
        val parse = (result["findings"] as List<Map<String, Any?>>).filter { it["check"] == "parseIssues" }
        assertTrue("no parse issue: $parse", parse.isEmpty())
    }
}
