package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A REST call links to the handler for its verb. `GET /api/orders/{orderNumber}` and
 * `POST /api/orders/archive` share a path shape — the variable takes `archive` — and path-only matching
 * linked each operation to both handlers, so the endpoint pages listed callers that never reach them.
 */
class RestVerbMatchTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-rest-verb-test").toFile()
            File(dir, "order.service").writeText(
                """{"key":"orderService","name":"Order Service","type":"rest","operations":[
                    {"key":"findByNumber","name":"Find by number","config":{"method":"GET","url":"/api/orders/{orderNumber}"}},
                    {"key":"archive","name":"Archive","config":{"method":"POST","url":"/api/orders/archive"}},
                    {"key":"replace","name":"Replace","config":{"method":"PUT","url":"/api/orders/archive"}}
                  ]}""")
            File(dir, "src/main/java/com/example").mkdirs()
            File(dir, "src/main/java/com/example/OrderController.java").writeText(
                """package com.example;
                  |import org.springframework.web.bind.annotation.*;
                  |@RestController
                  |@RequestMapping("/api/orders")
                  |public class OrderController {
                  |    @GetMapping("/{orderNumber}")
                  |    public String byNumber(@PathVariable String orderNumber) { return "{}"; }
                  |    @PostMapping("/archive")
                  |    public String archive() { return "ok"; }
                  |}
                  |""".trimMargin())
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun call(where: String) = (result["restCalls"] as List<Map<String, Any?>>).single { it["where"] == where }

    @Suppress("UNCHECKED_CAST")
    private fun edges() = ((result["graph"] as Map<String, Any?>)["edges"] as List<Map<String, Any?>>)
        .filter { it["rel"] == "rest-call" && it["s"] == "service:orderService" }

    @Test
    fun aRestCallLinksOnlyToTheHandlerForItsVerb() {
        @Suppress("UNCHECKED_CAST")
        val get = call("findByNumber")["matches"] as List<String>
        assertEquals(get.toString(), 1, get.size)
        assertTrue(get.single(), get.single().startsWith("GET /api/orders/{orderNumber}"))
        @Suppress("UNCHECKED_CAST")
        val post = call("archive")["matches"] as List<String>
        assertEquals(post.toString(), listOf("POST /api/orders/archive"), post.map { it.substringBefore(" ->") })
    }

    @Test
    fun aVerbNoHandlerServesIsASuspectLinkNotAClaim() {
        @Suppress("UNCHECKED_CAST")
        val put = call("replace")
        assertEquals(emptyList<String>(), put["matches"])
        @Suppress("UNCHECKED_CAST")
        val loose = put["looseMatches"] as List<String>
        assertTrue(loose.toString(), loose.any { it.startsWith("POST /api/orders/archive") && it.endsWith("(verb differs)") })
    }

    @Test
    fun eachEndpointIsReachedOnlyByTheCallsForItsVerb() {
        val clean = edges().filter { it["suspect"] != true }.map { it["t"] }.toSet()
        assertEquals(setOf("endpoint:GET /api/orders/{orderNumber}", "endpoint:POST /api/orders/archive"), clean)
    }
}
