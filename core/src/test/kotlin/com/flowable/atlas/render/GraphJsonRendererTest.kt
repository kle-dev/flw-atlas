package com.flowable.atlas.render

import com.flowable.atlas.graph.Atlas
import com.flowable.atlas.model.Dyn
import com.flowable.atlas.model.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * The graph.json projection: a `_schema` and a `_generated` block up front, `dataIn` pointers instead
 * of a second copy of every model and changelog body, Java classes as references, and a `usedBy` reverse
 * index on referenced nodes. `GraphSizeGuardTest` checks the file as a whole for copies.
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

    /**
     * A member that is null is not written: a query reads `.file` as null either way, and the key was
     * in every record an agent cut from the file. The projection keeps it — only the writing drops it.
     */
    @Test
    fun aNullMemberIsNotWritten() {
        val text = GraphJsonRenderer.render(result, generatedAt = Instant.parse("2026-09-02T10:00:00Z"))
        assertFalse("no `\"key\":null` in the file", Regex("\":null[,}]").containsMatchIn(text))
        assertFalse("…nor in the indented one",
            Regex("\": null[,\\n]").containsMatchIn(GraphJsonRenderer.render(result, pretty = true, generatedAt = Instant.parse("2026-09-02T10:00:00Z"))))
        @Suppress("UNCHECKED_CAST")
        val projected = (GraphJsonRenderer.project(result)["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>
        assertTrue("the fixture has a node without a file, so the test tests something",
            projected.any { it.containsKey("file") && it["file"] == null })
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

    /**
     * Variables, expressions and service operations are never an edge's target; the models using them
     * are in their `data.usedBy`. The node-level `usedBy` — the recipe CLAUDE.md gives — must carry
     * them, and the data must not carry them a second time.
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun everyNodeCarriesItsReferrersAtNodeLevelEvenWhenNoEdgePointsAtIt() {
        val source = ((result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>).associateBy { it["id"] }
        val projected = (rendered()["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>
        var moved = 0
        for (n in projected) {
            val data = n["data"] as? Map<String, Any?> ?: continue
            if (data.containsKey("dataIn")) continue
            assertFalse("${n["id"]} still carries data.usedBy", data.containsKey("usedBy"))
            val own = (source[n["id"]]?.get("data") as? Map<String, Any?>)?.get("usedBy") as? List<String> ?: continue
            if (own.isEmpty()) continue
            val declared = n["usedBy"] as? List<String> ?: emptyList()
            assertTrue("${n["id"]}: $declared lacks $own", declared.containsAll(own))
            moved++
        }
        assertTrue("the fixture has variables, expressions and an operation", moved > 30)
        val variable = projected.single { it["id"] == "variable:total" }
        assertTrue((variable["usedBy"] as List<String>).contains("process:orderProcess"))
        // The extract result itself is shared with the other renderers and is never changed.
        val sourceTotal = source["variable:total"]!!["data"] as Map<String, Any?>
        assertTrue("the projection must not mutate the result", sourceTotal.containsKey("usedBy"))
    }

    /**
     * A changelog node's data is a slim copy of its `liquibase` entry rather than the entry itself, so the
     * identity match that projects model nodes missed it and every changelog was written twice.
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun aChangelogNodePointsIntoTheLiquibaseBucket() {
        val json = rendered()
        val nodes = (json["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>
        val changelogs = nodes.filter { it["type"] == "liquibase" }
        assertTrue("the fixture has a changelog", changelogs.any { it["id"] == "liquibase:001-customer" })
        val bucket = json["liquibase"] as List<Map<String, Any?>>
        for (n in changelogs) {
            assertEquals("${n["id"]} points at its body", mapOf("dataIn" to "liquibase"), n["data"])
            val entry = bucket.single { it["key"] == n["key"] }
            assertTrue("the body is in the bucket: ${entry.keys}", "columns" in entry && "tables" in entry)
        }
        // The explorer embeds node data, so the in-memory node keeps its copy.
        val inMemory = Dyn.maps(Dyn.mapOrNull(result["graph"])!!["nodes"]).single { it["id"] == "liquibase:001-customer" }
        assertTrue("the projection does not touch the shared result", "columns" in (inMemory["data"] as Map<String, Any?>))
    }

    /**
     * The Java buckets hold references, not the parser's records: those carry imports, string literals and
     * call sites — about thirty keys per class, once per role — and `java:<fqn>` already has what a reader
     * uses. `javaControllers` is `javaByRole.controller`, and the expression and binding key lists are the
     * keys of those nodes, so none of the three is written.
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun rawJavaParseRecordsAndDuplicateKeyListsStayOutOfTheFile() {
        val text = GraphJsonRenderer.render(result, generatedAt = Instant.EPOCH)
        for (key in listOf("imports", "literalCalls", "strings", "keyedStrings", "literalFacts")) {
            assertFalse("graph.json carries the parser's \"$key\"", text.contains("\"$key\":"))
        }
        val json = MiniJson.parse(text) as Map<String, Any?>
        for (gone in listOf("javaControllers", "expressions", "placeholders")) {
            assertFalse("graph.json still writes $gone", json.containsKey(gone))
        }
        val byRole = json["javaByRole"] as Map<String, List<Map<String, Any?>>>
        assertTrue("the fixture has a controller", byRole["controller"].orEmpty().isNotEmpty())
        for ((role, refs) in byRole) for (ref in refs) {
            assertTrue("$role: $ref is a reference", ref.keys.isNotEmpty() && setOf("fqn", "file", "line").containsAll(ref.keys))
        }
        val nodeIds = ((json["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>).map { it["id"] }.toSet()
        for ((role, refs) in byRole) if (role != "other") for (ref in refs) {
            assertTrue("${ref["fqn"]} has the node its details are on", "java:${ref["fqn"]}" in nodeIds)
        }
        // The overview reads the full records in the same run.
        val raw = (result["javaByRole"] as Map<String, List<Map<String, Any?>>>).getValue("controller").first()
        assertTrue("the projection does not touch the shared result", "imports" in raw)
        assertNotNull(result["javaControllers"])
    }
}
