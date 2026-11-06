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

    /**
     * Which data-object field a column carries is decided by the service mapping, not by the column's
     * own name. The fixture's `delivery_city_` / `delivery_zip_` are mapped crossed on purpose, so a row
     * matched on the column name would list both fields — and list the wrong one first.
     */
    @Test
    fun aRowNamesTheDataObjectFieldItsMappingBindsTo() {
        val fixtureDir = fixtureDir()
        val result = Atlas.extract(fixtureDir)
        val xmls = Discovery.discover(fixtureDir).xmls
        LiquibaseCoverage.apply(result, xmls, fixtureDir)

        val coverage = mapList(result["services"]).first { it["key"] == "customerService" }["schemaCoverage"]
        val rows = mapList((coverage as Map<*, *>)["rows"])
        fun fieldsOf(sql: String) = mapList(rows.first { it["sql"] == sql }["dataObjects"]).map { it["field"] }

        assertEquals(listOf("deliveryZip"), fieldsOf("delivery_city_"))
        assertEquals(listOf("deliveryCity"), fieldsOf("delivery_zip_"))
    }

    /**
     * A relation as Design writes it has no column: its rows are in a join table of their own. Its mapping
     * is no column the service maps "but Liquibase does not have" — that row was on every service that
     * relates to another — and the table's columns are covered as they are.
     */
    @Test
    fun aRelationMappingIsNoColumnOfTheTable() {
        val dir = java.nio.file.Files.createTempDirectory("atlas-relation-coverage").toFile()
        try {
            File(dir, "db/DEMO-L1.xml").apply { parentFile.mkdirs() }.writeText("""<?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"><changeSet id="1" author="demo">
                <createTable tableName="DEMO_ORDER_"><column name="ID_" type="varchar(64)"/></createTable>
                <createTable tableName="DEMO_ORDER_CUSTOMER_"><column name="DEMO_ORDER_ID_" type="varchar(64)"/><column name="DEMO_CUSTOMER_ID_" type="varchar(64)"/></createTable>
                </changeSet></databaseChangeLog>""")
            File(dir, "services/order.service").apply { parentFile.mkdirs() }.writeText("""{"key": "DEMO-S1", "name": "Orders", "type": "database",
                "tableName": "DEMO_ORDER_", "referencedLiquibaseModelKey": "DEMO-L1", "lookupId": "id",
                "columnMappings": [{"name": "id", "columnName": "ID_", "type": "string"},
                  {"name": "customer", "type": "object-relation",
                   "relation": {"joinTableName": "DEMO_ORDER_CUSTOMER_", "owningColumnName": "DEMO_ORDER_ID_", "referenceColumnName": "DEMO_CUSTOMER_ID_"}}]}""")
            val result = Atlas.extract(dir)
            val coverage = mapList(result["services"]).single { it["key"] == "DEMO-S1" }["schemaCoverage"] as Map<*, *>
            assertEquals(listOf("ID_" to "no-dataobject"), mapList(coverage["rows"]).map { it["sql"] to it["status"] })
        } finally {
            dir.deleteRecursively()
        }
    }
}
