package com.flowable.atlas.generate.liquibase

import com.flowable.atlas.parsing.DataField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
