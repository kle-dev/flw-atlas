package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * An app contains the models it lists and the models packed beside it. Both are `contains` edges — the
 * rest of the graph treats them alike — but a member that is only co-located says so, because the app
 * definition not listing it is exactly what an app's completeness table has to show.
 */
class AppColocationTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-app-colocation-test").toFile()
            File(dir, "demo.app").writeText(
                """{"key":"DEMO-app","name":"App","extension":{"design":{"childModels":[{"key":"DEMO-listed","type":"bpmn"}]}}}""")
            for (key in listOf("DEMO-listed", "DEMO-beside")) {
                File(dir, "$key.bpmn").writeText(
                    """<?xml version="1.0" encoding="UTF-8"?>
                      |<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"><process id="$key"><startEvent id="s"/></process></definitions>
                      |""".trimMargin())
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
    private fun contains() = ((result["graph"] as Map<String, Any?>)["edges"] as List<Map<String, Any?>>)
        .filter { it["s"] == "app:DEMO-app" && it["rel"] == "contains" }.associateBy { it["t"] }

    @Test
    fun aModelBesideTheAppButNotListedIsMarkedColocated() {
        val c = contains()
        assertEquals(setOf("process:DEMO-listed", "process:DEMO-beside"), c.keys)
        assertEquals(true, c["process:DEMO-beside"]!!["colocated"])
    }

    @Test
    fun aListedMemberIsDeclaredEvenWhenItAlsoSitsBesideTheApp() {
        assertTrue("one edge per member", contains().size == 2)
        assertEquals(null, contains()["process:DEMO-listed"]!!["colocated"])
    }
}
