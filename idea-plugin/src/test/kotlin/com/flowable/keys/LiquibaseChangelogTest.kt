package com.flowable.keys

import com.flowable.keys.liquibase.LiquibaseChangelog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the Liquibase changelog parsing/replay ported from flowable_atlas.py. */
class LiquibaseChangelogTest {

    private val createTableXml = """
        <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
          <property name="serviceDefinitionReferences" value="DEMO-S010"/>
          <changeSet id="1" author="flowable">
            <createTable tableName="DB_INVOKER_ORDER">
              <column name="ID_" type="${'$'}{varchar.type}(255)"><constraints primaryKey="true"/></column>
              <column name="NAME_" type="${'$'}{varchar.type}(255)"/>
              <column name="BOGUS_" type="${'$'}{varchar.type}(255)"/>
            </createTable>
          </changeSet>
        </databaseChangeLog>
    """.trimIndent()

    @Test fun loose_bridges_sql_and_logical_names() {
        assertEquals("crewid", LiquibaseChangelog.loose("CREW_ID_"))
        assertEquals("crewid", LiquibaseChangelog.loose("crewId"))
        assertEquals("crewid", LiquibaseChangelog.loose("crew_id"))
    }

    @Test fun changelogKey_strips_prefix_and_suffix() {
        assertEquals("APP-L003", LiquibaseChangelog.changelogKey("liquibase-APP-L003.data.changelog.xml"))
        // Faithful to flowable_atlas: only .data.changelog.xml / .changelog.xml / .xml / .sql are stripped,
        // so a user-authored *-db-changelog.xml keeps its suffix (it carries no model key anyway).
        assertEquals("order-db-changelog", LiquibaseChangelog.changelogKey("order-db-changelog.xml"))
    }

    @Test fun parseOps_reads_createTable_columns() {
        val ops = LiquibaseChangelog.parseOps(createTableXml)
        val table = ops.filterIsInstance<LiquibaseChangelog.Op.TableColumns>().single()
        assertEquals("DB_INVOKER_ORDER", table.table)
        assertEquals(listOf("ID_", "NAME_", "BOGUS_"), table.columns.map { it.name })
    }

    @Test fun liquibaseType_maps_logical_types() {
        assertEquals("\${varchar.type}(255)", LiquibaseChangelog.liquibaseType("STRING"))
        assertEquals("bigint", LiquibaseChangelog.liquibaseType("LONG"))
        assertEquals("integer", LiquibaseChangelog.liquibaseType("int"))
        assertEquals("longtext", LiquibaseChangelog.liquibaseType("JSON"))
        assertEquals("date", LiquibaseChangelog.liquibaseType("LOCAL-DATE"))
        assertEquals(null, LiquibaseChangelog.liquibaseType("UNKNOWN"))
    }

    @Test fun serviceReferences_are_parsed() {
        assertEquals(setOf("DEMO-S010"), LiquibaseChangelog.serviceReferences(createTableXml))
    }

    @Test fun unmapped_flags_columns_absent_from_service() {
        val ops = LiquibaseChangelog.parseOps(createTableXml)
        val serviceCols = setOf("id", "name") // loose of ID_/NAME_
        val unmapped = LiquibaseChangelog.unmappedLooseNames(ops, serviceCols)
        assertTrue("BOGUS_ must be flagged: $unmapped", unmapped.contains("bogus"))
        assertFalse("ID_ must not be flagged", unmapped.contains("id"))
        assertFalse("NAME_ must not be flagged", unmapped.contains("name"))
    }

    @Test fun unmapped_honours_dropColumn_in_same_file() {
        val xml = createTableXml.replace(
            "</changeSet>",
            """</changeSet>
               <changeSet id="2" author="flowable">
                 <dropColumn tableName="DB_INVOKER_ORDER" columnName="BOGUS_"/>
               </changeSet>""",
        )
        val ops = LiquibaseChangelog.parseOps(xml)
        val unmapped = LiquibaseChangelog.unmappedLooseNames(ops, setOf("id", "name"))
        assertFalse("BOGUS_ dropped later must not be flagged: $unmapped", unmapped.contains("bogus"))
    }

    @Test fun unmapped_honours_renameColumn_in_same_file() {
        val xml = createTableXml.replace(
            "</changeSet>",
            """</changeSet>
               <changeSet id="2" author="flowable">
                 <renameColumn tableName="DB_INVOKER_ORDER" oldColumnName="BOGUS_" newColumnName="NAME_"/>
               </changeSet>""",
        )
        val ops = LiquibaseChangelog.parseOps(xml)
        // BOGUS_ renamed to NAME_ (which IS in the service) → nothing flagged.
        val unmapped = LiquibaseChangelog.unmappedLooseNames(ops, setOf("id", "name"))
        assertTrue("renamed-away column must not be flagged: $unmapped", unmapped.isEmpty())
    }
}
