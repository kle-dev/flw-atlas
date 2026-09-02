package com.flowable.atlas.render

import com.flowable.atlas.graph.Atlas
import com.flowable.atlas.model.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * The graph.json projection: a `_schema` and a `_generated` block up front, `dataIn` pointers instead
 * of a second copy of every model body, and a `usedBy` reverse index on referenced nodes.
 */
class GraphJsonRendererTest {

    companion object {
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            result = Atlas.extract(File("src/test/resources/miniproject"))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rendered(): Map<String, Any?> =
        MiniJson.parse(GraphJsonRenderer.render(result, generatedAt = Instant.parse("2026-09-02T10:00:00Z"))) as Map<String, Any?>

    @Test
    fun theFileDescribesItself() {
        val json = rendered()
        assertEquals("_schema comes first", "_schema", json.keys.first())
        @Suppress("UNCHECKED_CAST")
        val generated = json["_generated"] as Map<String, Any?>
        assertEquals("2026-09-02T10:00:00Z", generated["at"])
        assertTrue("the Atlas version is stated", (generated["atlas"] as String).isNotBlank())
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun modelBodiesAreProjectedToPointersAndReferencesAreIndexedBothWays() {
        val graph = rendered()["graph"] as Map<String, Any?>
        val nodes = graph["nodes"] as List<Map<String, Any?>>
        val process = nodes.single { it["id"] == "process:orderProcess" }
        assertEquals("a model node's body lives in its bucket, the node points at it",
            mapOf("dataIn" to "processes"), process["data"])
        val form = nodes.single { it["id"] == "form:orderForm" }
        assertNotNull("a referenced node carries who references it", form["usedBy"])
        assertTrue((form["usedBy"] as List<String>).contains("process:orderProcess"))
    }
}
