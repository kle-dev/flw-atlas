package com.flowable.atlas.liquibase

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which columns are a table's primary key once every change set has run — the key the ER diagram designer
 * marks and puts first on a card. Declared inline (`<constraints primaryKey="true"/>`, `… PRIMARY KEY`),
 * named later (`<addPrimaryKey>`, `PRIMARY KEY (a, b)`), kept through a rename, gone after a drop.
 */
class LiquibasePrimaryKeyTest {

    private fun changelog(body: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">$body</databaseChangeLog>"""

    private fun keys(vararg files: Pair<String, String>, table: String): List<String> {
        val r = LiquibaseReplay.replay(files.map { (rel, text) -> LiquibaseReplay.File(rel, LiquibaseParser.parse(text, rel)!!) })
        return r.runs.first { it.kind == "application" }.schema.table(table)!!.columns.values.filter { it.pk }.map { it.name }
    }

    @Test
    fun anInlineConstraintSurvivesARename() {
        val pk = keys("db/changelog/customer.xml" to changelog("""
            <changeSet id="1" author="demo"><createTable tableName="DEMO_CUSTOMER_">
              <column name="ID_" type="varchar(64)"><constraints primaryKey="true" nullable="false"/></column>
              <column name="NAME_" type="varchar(255)"><constraints nullable="false"/></column>
            </createTable></changeSet>
            <changeSet id="2" author="demo"><renameColumn tableName="DEMO_CUSTOMER_" oldColumnName="ID_" newColumnName="CUSTOMER_ID_"/></changeSet>"""),
            table = "DEMO_CUSTOMER_")
        assertEquals(listOf("CUSTOMER_ID_"), pk)
    }

    @Test
    fun addPrimaryKeyNamesTheKeyAndDropPrimaryKeyRemovesIt() {
        val file = "db/changelog/line.xml" to changelog("""
            <changeSet id="1" author="demo"><createTable tableName="DEMO_LINE_">
              <column name="ORDER_ID_" type="varchar(64)"/><column name="POS_" type="int"/><column name="QTY_" type="int"/>
            </createTable></changeSet>
            <changeSet id="2" author="demo"><addPrimaryKey tableName="DEMO_LINE_" columnNames="ORDER_ID_, POS_" constraintName="PK_DEMO_LINE"/></changeSet>""")
        assertEquals(listOf("ORDER_ID_", "POS_"), keys(file, table = "DEMO_LINE_"))
        val dropped = file.first to file.second.replace("</databaseChangeLog>",
            """<changeSet id="3" author="demo"><dropPrimaryKey tableName="DEMO_LINE_"/></changeSet></databaseChangeLog>""")
        assertEquals(emptyList<String>(), keys(dropped, table = "DEMO_LINE_"))
    }

    @Test
    fun aKeyInsideARollbackIsNotOne() {
        val pk = keys("db/changelog/t.xml" to changelog("""
            <changeSet id="1" author="demo"><createTable tableName="DEMO_T_"><column name="A_" type="int"/></createTable>
              <rollback><addPrimaryKey tableName="DEMO_T_" columnNames="A_"/></rollback></changeSet>"""),
            table = "DEMO_T_")
        assertEquals(emptyList<String>(), pk)
    }

    @Test
    fun sqlDeclaresTheKeyInlineOrAsATableConstraint() {
        val inline = SqlDdl.parse("CREATE TABLE DEMO_A_ (ID_ VARCHAR(64) NOT NULL PRIMARY KEY, NAME_ VARCHAR(255) DEFAULT 'x')")
        assertEquals(listOf(true, false), (inline.single() as LbChange.CreateTable).columns.map { it.pk })

        val constraint = SqlDdl.parse("CREATE TABLE DEMO_B_ (ID_ BIGINT, POS_ INT, CONSTRAINT PK_DEMO_B PRIMARY KEY (ID_, POS_))")
        assertEquals(LbChange.PrimaryKey("DEMO_B_", listOf("ID_", "POS_")), constraint.last())
        assertEquals("a table constraint is no column", listOf("ID_", "POS_"), (constraint.first() as LbChange.CreateTable).columns.map { it.name })

        assertEquals(listOf(LbChange.PrimaryKey("DEMO_C_", listOf("ID_"))), SqlDdl.parse("ALTER TABLE DEMO_C_ ADD CONSTRAINT PK_C PRIMARY KEY (ID_)"))
        assertEquals(listOf(LbChange.DropPrimaryKey("DEMO_C_")), SqlDdl.parse("ALTER TABLE DEMO_C_ DROP PRIMARY KEY"))
        assertEquals("a type's own parentheses hold no key", false,
            (SqlDdl.parse("CREATE TABLE DEMO_D_ (X_ DECIMAL(19,2))").single() as LbChange.CreateTable).columns.single().pk)
    }
}
