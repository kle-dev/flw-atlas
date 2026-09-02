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
 * Two models of different types may share one key. Both have to survive the dedupe, a typed
 * reference has to land on the model of its own type, and the ambiguity a key-only lookup is left
 * with has to be said — once, as a warning.
 */
class SharedKeyGraphTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-shared-key-test").toFile()
            // Discovery is name-sorted, so the page is registered before the form: the key-only map
            // points at the page, and only a type-aware lookup reaches the form.
            File(dir, "a-shared.page").writeText(
                """{"metadata":{"key":"shared","name":"Shared page","modelType":"page"},"rows":[]}""")
            File(dir, "z-shared.form").writeText(
                """{"metadata":{"key":"shared","name":"Shared form","modelType":"form"},"rows":[]}""")
            File(dir, "p.bpmn").writeText(
                """<definitions xmlns:flowable="http://flowable.org/bpmn">
                     <process id="p"><userTask id="t" flowable:formKey="shared"/></process>
                   </definitions>""")
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun graph(part: String): List<Map<String, Any?>> =
        ((result["graph"] as Map<String, Any?>)[part] as List<Map<String, Any?>>)

    @Test
    fun bothModelsSurviveTheDedupe() {
        val ids = graph("nodes").map { it["id"] }
        assertTrue("the form was dropped as a duplicate of the page", "form:shared" in ids)
        assertTrue("the page was dropped as a duplicate of the form", "page:shared" in ids)
    }

    @Test
    fun aTypedReferenceLandsOnItsOwnType() {
        val targets = graph("edges").filter { it["s"] == "process:p" }.map { it["t"] }
        assertTrue("the formKey should reach the form, edges go to $targets", "form:shared" in targets)
        assertFalse("the formKey must not reach the page of the same key", "page:shared" in targets)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun theSharedKeyIsSaidOnceAsAWarning() {
        val conflicts = (result["diagnostics"] as List<Map<String, Any?>>).filter { it["kind"] == "conflict" }
        assertEquals("one shared key, one diagnostic", 1, conflicts.size)
        assertTrue(conflicts.single()["message"].toString().contains("'shared'"))
        val parse = (result["findings"] as List<Map<String, Any?>>).filter { it["check"] == "parseIssues" }
        assertEquals("warning", parse.single()["severity"])
    }
}
