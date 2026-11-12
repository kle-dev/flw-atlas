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
                """{"metadata":{"key":"shared","name":"Shared page","modelType":"page"},
                    "rows":[{"cols":[{"id":"pageField","type":"text","value":"{{pageOnly}}"},
                      {"id":"pageButton","type":"restButton","extraSettings":{"url":"api/page-things"}}]}]}""")
            File(dir, "z-shared.form").writeText(
                """{"metadata":{"key":"shared","name":"Shared form","modelType":"form"},
                    "rows":[{"cols":[{"id":"formField","type":"text","value":"{{formOnly}}"}]}]}""")
            // A service of the same key, registered after both: the key-only map never points at it.
            File(dir, "shared.service").writeText(
                """{"key":"shared","name":"Shared service","type":"rest","operations":[
                    {"key":"get","name":"Get","config":{"method":"GET","url":"/api/shared-things/{id}"}}]}""")
            File(dir, "src/main/java/com/example").mkdirs()
            File(dir, "src/main/java/com/example/ThingController.java").writeText(
                """package com.example;
                  |import org.springframework.web.bind.annotation.*;
                  |@RestController
                  |public class ThingController {
                  |    @GetMapping("/api/shared-things/{id}")
                  |    public String shared(@PathVariable String id) { return "{}"; }
                  |    @GetMapping("/api/page-things")
                  |    public String page() { return "[]"; }
                  |}
                  |""".trimMargin())
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
    fun theSharedKeyIsSaidOncePerTypeAndIsNotAFinding() {
        val conflicts = (result["diagnostics"] as List<Map<String, Any?>>).filter { it["kind"] == "conflict" }
        // one per type that joins the key: the form and the service after the page
        assertEquals("one shared key, one diagnostic per further type", 2, conflicts.size)
        assertTrue(conflicts.all { it["message"].toString().contains("'shared'") })
        // Nothing failed to parse, and nothing harvested is mis-credited any more: not a parse issue.
        val parse = (result["findings"] as List<Map<String, Any?>>).filter { it["check"] == "parseIssues" }
        assertTrue("a shared key is information, not a finding: $parse", parse.isEmpty())
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun whatEachFileCarriesIsCreditedToTheModelOfItsOwnType() {
        // The key-only map points at the page (registered first); before, both bindings landed there and
        // the form's page showed none of its own.
        fun usedBy(id: String) = (graph("nodes").single { it["id"] == id }["data"] as Map<String, Any?>)["usedBy"]
        assertEquals(listOf("form:shared"), usedBy("binding:{{formOnly}}"))
        assertEquals(listOf("page:shared"), usedBy("binding:{{pageOnly}}"))
        val formOnly = graph("nodes").single { it["id"] == "variable:formOnly" }["data"] as Map<String, Any?>
        assertEquals(listOf("form:shared"), formOnly["usedBy"])
    }

    @Test
    fun aRestCallStaysWithTheModelThatMakesIt() {
        // Resolved by key alone, the service's operation call landed on the page (registered first), and
        // the page's own page listed an endpoint it never calls.
        val calls = graph("edges").filter { it["rel"] == "rest-call" }.associate { it["s"] to it["t"] }
        assertEquals(mapOf(
            "service:shared" to "endpoint:GET /api/shared-things/{id}",
            "page:shared" to "endpoint:GET /api/page-things",
        ), calls)
    }

    @Test
    fun aRestCallEdgeNamesTheUrlsThatReachIt() {
        val edge = graph("edges").single { it["rel"] == "rest-call" && it["s"] == "service:shared" }
        assertEquals(listOf("/api/shared-things/{id}"), edge["via"])
    }
}
