package com.flowable.atlas.generate.liquibase

import com.flowable.atlas.liquibase.LiquibaseChangelog
import com.flowable.atlas.liquibase.LiquibaseParser
import com.flowable.atlas.liquibase.LiquibaseReplay
import com.flowable.atlas.parsing.ColumnMapping
import com.flowable.atlas.parsing.ColumnRelation
import com.flowable.atlas.parsing.DataField
import com.flowable.atlas.parsing.ServiceTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for the synthesize shape/types, the master skeleton, and idempotent include insertion. */
class LiquibaseChangelogGeneratorTest {

    // ---- synthesize -------------------------------------------------------------------------

    @Test fun synthesize_builds_createTable_with_a_column_per_field() {
        val xml = LiquibaseChangelogGenerator.synthesize(
            "DEMO-DO-0001", "DEMO_ORDER",
            listOf(DataField("orderId", "STRING"), DataField("total", "DOUBLE")),
        )
        assertTrue("declares the table", xml.contains("<createTable tableName=\"DEMO_ORDER\">"))
        assertTrue("column for orderId", xml.contains("<column name=\"orderId\" type=\"\${varchar.type}(255)\"/>"))
        assertTrue("column for total", xml.contains("<column name=\"total\" type=\"double\"/>"))
        assertTrue("is a databaseChangeLog", xml.contains("<databaseChangeLog"))
    }

    @Test fun synthesize_maps_types_via_liquibaseType_and_defaults_unknown_to_varchar() {
        val xml = LiquibaseChangelogGenerator.synthesize(
            "k", "T",
            listOf(DataField("d", "LOCAL-DATE"), DataField("weird", "SOMETHING-ELSE")),
        )
        assertTrue("LOCAL-DATE → date", xml.contains("<column name=\"d\" type=\"date\"/>"))
        assertTrue("unknown type falls back to varchar", xml.contains("<column name=\"weird\" type=\"\${varchar.type}(255)\"/>"))
    }

    @Test fun synthesize_falls_back_to_key_when_table_blank_and_handles_no_fields() {
        val xml = LiquibaseChangelogGenerator.synthesize("DEMO-DO-0002", "", emptyList())
        assertTrue("blank table name uses the key", xml.contains("tableName=\"DEMO-DO-0002\""))
        assertTrue("empty createTable is still valid", xml.contains("<createTable"))
    }

    // ---- from the backing service ------------------------------------------------------------

    /** The shape of the public demo's order service: every column named apart from its field. */
    private val orderService = ServiceTable(
        key = "DEMO-orderService", type = "database", tableName = "DEMO_ORDER", referenceKey = null,
        referencedLiquibaseModelKey = null, lookupId = "id",
        columns = listOf(
            ColumnMapping("id", "ID_", "STRING"),
            ColumnMapping("orderNumber", "ORDER_NO_", "STRING"),
            ColumnMapping("total", "TOTAL_", "DOUBLE"),
            ColumnMapping("customer", null, "object-relation", ColumnRelation("DEMO_ORDER_CUSTOMER", "ORDER_ID_", "CUSTOMER_ID_")),
            // the demo's own shape: a relation that names no join table creates nothing
            ColumnMapping("company", "COMPANY_ID_", "object-relation", ColumnRelation(null, null, "ID_")),
        ),
    )

    @Test fun fromService_names_columns_as_the_service_reads_them() {
        val xml = LiquibaseChangelogGenerator.fromService(orderService)!!
        assertTrue(xml, xml.contains("<createTable tableName=\"DEMO_ORDER\">"))
        assertTrue(xml, xml.contains("<column name=\"ORDER_NO_\" type=\"\${varchar.type}(255)\"/>"))
        assertTrue(xml, xml.contains("<column name=\"TOTAL_\" type=\"double\"/>"))
        assertFalse("never the logical field name: $xml", xml.contains("name=\"orderNumber\""))
        assertFalse("a relation is not a column of the table: $xml", xml.contains("COMPANY_ID_"))
    }

    /** Read back by the same replay the explorer runs: the table holds the service's columns, keyed by the lookup id. */
    @Test fun fromService_makes_the_lookup_id_the_primary_key() {
        val rel = "src/main/resources/DEMO.changelog.xml"
        val parsed = LiquibaseParser.parse(LiquibaseChangelogGenerator.fromService(orderService)!!, rel)
        assertNotNull("the changelog parses", parsed)
        val run = LiquibaseReplay.replay(listOf(LiquibaseReplay.File(rel, parsed!!))).runs.first()
        val table = run.schema.tables.getValue("DEMO_ORDER")
        assertEquals(listOf("ID_", "ORDER_NO_", "TOTAL_"), table.columns.keys.toList())
        assertTrue("the lookup id's column is the primary key", table.columns.getValue("ID_").pk)
        assertFalse(table.columns.getValue("ORDER_NO_").pk)
    }

    @Test fun fromService_creates_each_join_table_only_if_missing() {
        val xml = LiquibaseChangelogGenerator.fromService(orderService)!!
        assertEquals(setOf("DEMO_ORDER", "DEMO_ORDER_CUSTOMER"), LiquibaseChangelog.tableNames(xml))
        assertTrue(xml, xml.contains("<changeSet id=\"2\" author=\"flowable-atlas\">"))
        assertTrue("either side of a relation may create it: $xml", xml.contains("<tableExists tableName=\"DEMO_ORDER_CUSTOMER\"/>"))
        assertTrue(xml, xml.contains("<column name=\"ORDER_ID_\" type=\"\${varchar.type}(255)\"/>"))
        assertTrue(xml, xml.contains("indexName=\"FLW_IDX_DEMO_ORDER_CUSTOMER2\""))
    }

    @Test fun fromService_needs_a_table_and_a_column() {
        assertNull(LiquibaseChangelogGenerator.fromService(orderService.copy(tableName = " ")))
        assertNull(LiquibaseChangelogGenerator.fromService(orderService.copy(columns = orderService.columns.filter { it.relation != null })))
    }

    // ---- master skeleton --------------------------------------------------------------------

    @Test fun masterSkeleton_is_an_empty_dbchangelog_with_the_3_6_schema() {
        val master = LiquibaseChangelogGenerator.masterSkeleton()
        assertTrue(master.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(master.contains("xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\""))
        assertTrue(master.contains("dbchangelog-3.6.xsd"))
        assertTrue(master.contains("</databaseChangeLog>"))
        assertFalse("skeleton body is empty (no includes yet)", master.contains("<include"))
    }

    // ---- include insertion ------------------------------------------------------------------

    @Test fun withInclude_inserts_before_the_closing_tag() {
        val out = LiquibaseChangelogGenerator.withInclude(LiquibaseChangelogGenerator.masterSkeleton(), "DEMO.changelog.xml")
        assertTrue(out.contains("<include file=\"DEMO.changelog.xml\" relativeToChangelogFile=\"true\"/>"))
        assertTrue("include precedes the closing tag", out.indexOf("<include") < out.indexOf("</databaseChangeLog>"))
    }

    @Test fun withInclude_is_idempotent() {
        val once = LiquibaseChangelogGenerator.withInclude(LiquibaseChangelogGenerator.masterSkeleton(), "A.changelog.xml")
        val twice = LiquibaseChangelogGenerator.withInclude(once, "A.changelog.xml")
        assertEquals("re-running must not add a second include", once, twice)
        assertTrue(LiquibaseChangelogGenerator.includeExists(once, "A.changelog.xml"))
    }

    @Test fun withInclude_appends_a_second_distinct_include() {
        val a = LiquibaseChangelogGenerator.withInclude(LiquibaseChangelogGenerator.masterSkeleton(), "A.changelog.xml")
        val both = LiquibaseChangelogGenerator.withInclude(a, "B.changelog.xml")
        assertTrue(both.contains("file=\"A.changelog.xml\""))
        assertTrue(both.contains("file=\"B.changelog.xml\""))
    }
}
