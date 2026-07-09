package com.flowable.atlas.graph

import com.flowable.atlas.parsing.Discovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the [LiquibaseCoverage] port against the same `miniproject` fixture the Python suite uses,
 * asserting the intent-revealing facts ported from `tests/test_golden.py`
 * (`test_liquibase_coverage_links_service`, `test_dataobject_table_denormalized`).
 */
class LiquibaseCoverageTest {

    private fun fixtureDir(): File {
        val url = javaClass.classLoader.getResource("miniproject")
            ?: error("miniproject fixture not on the test classpath")
        return File(url.toURI())
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapList(v: Any?): List<Map<String, Any?>> =
        (v as? List<*>).orEmpty().mapNotNull { it as? Map<String, Any?> }

    @Test
    fun coverageLinksServiceAndDenormalizesDataObjects() {
        val fixtureDir = fixtureDir()
        val result = Atlas.extract(fixtureDir)
        val xmls = Discovery.discover(fixtureDir).xmls
        LiquibaseCoverage.apply(result, xmls, fixtureDir)

        // result["liquibase"] first entry: tables + authority.referencedBy
        val liquibase = mapList(result["liquibase"])
        assertTrue("expected at least one changelog", liquibase.isNotEmpty())
        val lb = liquibase[0]
        assertEquals(listOf("cust_customer"), lb["tables"])
        @Suppress("UNCHECKED_CAST")
        val authority = lb["authority"] as Map<String, Any?>
        assertEquals(listOf("customerService"), authority["referencedBy"])

        // dataObject customerDO: service table/type denormalized onto it
        val customerDO = mapList(result["dataObjects"]).first { it["key"] == "customerDO" }
        assertEquals("cust_customer", customerDO["serviceTableName"])
        assertEquals("db", customerDO["serviceType"])

        // service customerService: schemaCoverage map present
        val customerService = mapList(result["services"]).first { it["key"] == "customerService" }
        assertNotNull("customerService must carry a schemaCoverage map", customerService["schemaCoverage"])
        assertTrue(customerService["schemaCoverage"] is Map<*, *>)
    }
}
