package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Intent-revealing facts for the graph builder ([GraphBuilder]), ported from the matching cases in
 * `tests/test_golden.py`. Unlike the golden comparison these fail loudly with a reason and guard
 * against blindly regenerating a broken golden.
 */
class GraphBuilderTest {

    private val result: Map<String, Any?> by lazy { Atlas.extract(fixtureDir()) }

    private fun fixtureDir(): File {
        val url = javaClass.classLoader.getResource("miniproject")
            ?: error("miniproject fixture not on the test classpath")
        return File(url.toURI())
    }

    @Suppress("UNCHECKED_CAST")
    private fun graph() = result["graph"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun nodes() = graph()["nodes"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun edges() = graph()["edges"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun dataObjects() = result["dataObjects"] as List<Map<String, Any?>>

    // test_invalid_expression_is_flagged_in_graph
    @Test
    @Suppress("UNCHECKED_CAST")
    fun invalidExpressionIsFlaggedInGraph() {
        val node = nodes().first { it["id"] == "expression:\${vars:bogus(}" }
        val data = node["data"] as Map<String, Any?>
        val problems = data["problems"] as List<Map<String, Any?>>
        assertTrue(problems.toString(), problems.any { it["severity"] == "error" })
    }

    // test_dataobject_relation_edge
    @Test
    fun dataObjectRelationEdge() {
        assertTrue(
            "object-relation field mappings must become dataObject->dataObject edges",
            edges().any {
                it["s"] == "dataObject:customerDO" && it["t"] == "dataObject:priorityMD" && it["rel"] == "relates-to"
            },
        )
    }

    // test_external_node_for_unresolved_refs
    @Test
    @Suppress("UNCHECKED_CAST")
    fun externalNodeForUnresolvedRefs() {
        val byId = nodes().associateBy { it["id"] as String }
        assertTrue("unresolved beans must surface as external nodes", "external:notifierBean" in byId)
        val missing = byId["external:fulfilmentProcess"]
        assertNotNull("a referenced-but-undefined model key must surface as a missing-model node", missing)
        val data = missing!!["data"] as Map<String, Any?>
        assertEquals(true, data["missingModel"])
    }

    // test_masterdata_fields_extracted
    @Test
    @Suppress("UNCHECKED_CAST")
    fun masterDataFieldsExtracted() {
        val md = dataObjects().first { it["key"] == "priorityMD" }
        assertEquals("masterData `variables` map must become fields", listOf("level", "color"), md["fields"])
        assertEquals("key", md["keyField"])
        assertEquals("priority", md["subType"])
        val columns = md["columns"] as List<Map<String, Any?>>
        assertEquals("Level", columns[0]["label"])
    }

    // test_dataobject_table_denormalized
    @Test
    fun dataObjectTableDenormalized() {
        val doModel = dataObjects().first { it["key"] == "customerDO" }
        assertEquals("cust_customer", doModel["serviceTableName"])
        assertEquals("db", doModel["serviceType"])
    }
}
