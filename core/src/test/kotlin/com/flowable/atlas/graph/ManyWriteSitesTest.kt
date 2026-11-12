package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The write list shown on a variable is capped at 25; the unused-variable decision must not be. Here the
 * one silencing site — a mapping into a callee outside the project — sorts last of 26.
 */
class ManyWriteSitesTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-many-writes-test").toFile()
            val tasks = (1..25).joinToString("\n") {
                """<serviceTask id="t$it" flowable:expression="${'$'}{calc.run()}" flowable:resultVariableName="shared"/>"""
            }
            File(dir, "aaa.bpmn").writeText(
                """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="aaa">$tasks</process></definitions>""")
            File(dir, "zzz.bpmn").writeText(
                """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="zzz">
                     <callActivity id="c" calledElement="ghostProcess">
                       <extensionElements><flowable:in source="x" target="shared"/></extensionElements>
                     </callActivity>
                   </process></definitions>""")
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
    fun theSilencingSiteCountsEvenPastTheDisplayCap() {
        val v = ((result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>).single { it["id"] == "variable:shared" }
        val d = v["data"] as Map<String, Any?>
        assertEquals(26, d["writeCount"])
        assertEquals("the page shows 25", 25, (d["writes"] as List<*>).size)
        assertEquals("a write into a callee Atlas cannot see silences the check", true, d["readsUnknown"])
        assertTrue(d["unread"] == null)
        assertTrue((result["findings"] as List<Map<String, Any?>>).none { it["check"] == "unusedVars" && it["node"] == "variable:shared" })
    }
}
