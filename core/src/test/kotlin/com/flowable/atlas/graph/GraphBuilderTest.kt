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
        val missing = byId["external:courierProcess"]
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

    /**
     * A variable a script writes must name the script's element, and a variable that exists *only*
     * because a script mentions it must say so — that is the difference between "the process sets this"
     * and "a script seems to read this".
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun scriptVariablesCarryTheirElementAndFlagGuesses() {
        val byId = nodes().associateBy { it["id"] as String }
        val stamp = byId["variable:shippingStamp"] ?: error("a setVariable() in a script task must yield a variable")
        val stampData = stamp["data"] as Map<String, Any?>
        // the fixture's deliberately-broken `badStamp` task writes the same variable — pick the healthy one
        val site = (stampData["scriptSites"] as List<Map<String, Any?>>).single { it["element"] == "stampTask" }
        assertEquals("process:orderProcess", site["model"])
        assertEquals("stampTask", site["element"])
        assertEquals("Stamp order", site["elementName"])
        assertEquals(true, site["api"])
        assertEquals("an explicit API call is evidence, not a guess", null, stampData["heuristic"])

        // read bare out of the script's scope and nowhere else in the project
        val courier = byId["variable:courierCode"] ?: error("a bare identifier read in a script must yield a variable")
        val courierData = courier["data"] as Map<String, Any?>
        assertEquals(true, courierData["heuristic"])
        assertTrue(
            courierData["usages"].toString(),
            courierData["usages"].toString().contains("script ≈ read · Stamp order"),
        )

        // a listener's script is attributed to the element the listener hangs off
        val notified = byId["variable:notified"] ?: error("a listener script's variable must be indexed")
        val nSite = ((notified["data"] as Map<String, Any?>)["scriptSites"] as List<Map<String, Any?>>).single()
        assertEquals("notifyTask", nSite["element"])
        assertEquals("executionListener", nSite["elementType"])
    }

    /** Element-level `<documentation>` and listeners now travel with the element that declares them. */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun elementDocumentationAndListenersAreKept() {
        val processes = result["processes"] as List<Map<String, Any?>>
        val order = processes.first { it["key"] == "orderProcess" }
        val approve = (order["userTasks"] as List<Map<String, Any?>>).first { it["id"] == "approveTask" }
        assertEquals(
            "Backoffice checks the order total before it ships.",
            approve["documentation"],
        )
        val notify = (order["serviceTasks"] as List<Map<String, Any?>>).first { it["id"] == "notifyTask" }
        val ls = (notify["listeners"] as List<Map<String, Any?>>).single()
        assertEquals("executionListener", ls["kind"])
        assertEquals("end", ls["event"])
        assertTrue(ls["script"].toString().contains("setVariable"))
    }
}
