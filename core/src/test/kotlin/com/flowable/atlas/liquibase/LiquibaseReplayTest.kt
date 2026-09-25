package com.flowable.atlas.liquibase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a table holds once Liquibase has run every change set — replayed the way Liquibase runs them:
 * includes where they stand, `includeAll` in path order, a change set that already ran not again, a
 * failed precondition skipping one, and nothing from a `<rollback>` or a comment.
 */
class LiquibaseReplayTest {

    private fun changelog(body: String, logical: String? = null) = """<?xml version="1.0" encoding="UTF-8"?>
        <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"${logical?.let { " logicalFilePath=\"$it\"" } ?: ""}>
        $body
        </databaseChangeLog>"""

    private fun replay(vararg files: Pair<String, String>, sql: Map<String, String> = emptyMap()): LiquibaseReplay.Result =
        LiquibaseReplay.replay(files.map { (rel, text) -> LiquibaseReplay.File(rel, LiquibaseParser.parse(text, rel)!!) }, sql)

    private fun LiquibaseReplay.Result.table(name: String) = runs.first { it.kind == "application" }.schema.table(name)

    private fun LiquibaseReplay.Result.columns(name: String) = table(name)!!.columns.values.map { it.name }

    private fun LiquibaseReplay.Result.outcomes(rel: String) =
        runs.flatMap { r -> r.outcomes.entries.filter { it.key.rel == rel }.flatMap { it.value } }.map { it.changeSet.id to it.status }

    private val master = "src/main/resources/db/changelog/db.changelog-master.xml" to
        changelog("""<includeAll path="classpath*:db/changelog/versions"/>""")

    @Test
    fun theSameFileInEveryVersionFolderIsRead() {
        // v2, v3 and v4 each keep a customer.xml: all three are the table's history, none shadows another
        val r = replay(
            master,
            "src/main/resources/db/changelog/versions/v2/customer.xml" to changelog("""
                <changeSet id="1" author="demo"><createTable tableName="DEMO_CUSTOMER_">
                  <column name="ID_" type="varchar(64)"/><column name="NAME_" type="varchar(255)"/><column name="FAX_" type="varchar(32)"/>
                </createTable></changeSet>"""),
            "src/main/resources/db/changelog/versions/v3/customer.xml" to changelog("""
                <changeSet id="2" author="demo">
                  <addColumn tableName="DEMO_CUSTOMER_"><column name="EMAIL_" type="varchar(255)"/></addColumn>
                  <dropColumn tableName="DEMO_CUSTOMER_" columnName="FAX_"/>
                </changeSet>"""),
            "src/main/resources/db/changelog/versions/v4/customer.xml" to changelog("""
                <changeSet id="3" author="demo">
                  <renameColumn tableName="DEMO_CUSTOMER_" oldColumnName="NAME_" newColumnName="FULL_NAME_" columnDataType="varchar(255)"/>
                </changeSet>"""),
        )
        assertEquals(listOf("ID_", "EMAIL_", "FULL_NAME_"), r.columns("DEMO_CUSTOMER_"))
        val removed = r.table("DEMO_CUSTOMER_")!!.removed.associate { it.name to (it.renamedTo to it.by.file.substringAfter("versions/")) }
        assertEquals(mapOf("FAX_" to (null to "v3/customer.xml"), "NAME_" to ("FULL_NAME_" to "v4/customer.xml")), removed)
        assertEquals("the email column came from v3", "src/main/resources/db/changelog/versions/v3/customer.xml",
            r.table("DEMO_CUSTOMER_")!!.columns.getValue("EMAIL_").from.file)
    }

    @Test
    fun aChangeSetKeptInTwoFoldersUnderOneLogicalPathRunsOnce() {
        val create = """<changeSet id="1" author="demo"><createTable tableName="DEMO_ORDER_">
            <column name="ID_" type="varchar(64)"/></createTable></changeSet>"""
        val r = replay(
            master,
            "src/main/resources/db/changelog/versions/v2/order.xml" to changelog(create, logical = "DEMO-L-order"),
            "src/main/resources/db/changelog/versions/v3/order.xml" to changelog(create + """
                <changeSet id="2" author="demo"><addColumn tableName="DEMO_ORDER_"><column name="TOTAL_" type="numeric(10,2)"/></addColumn></changeSet>""",
                logical = "DEMO-L-order"),
        )
        assertEquals(listOf("1" to "duplicate", "2" to "ran"), r.outcomes("src/main/resources/db/changelog/versions/v3/order.xml"))
        assertEquals(listOf("ID_", "TOTAL_"), r.columns("DEMO_ORDER_"))
    }

    @Test
    fun aFailedPreconditionSkipsTheChangeSetAndAnEngineTableIsUnknown() {
        val r = replay(
            "db/a.xml" to changelog("""
                <changeSet id="1" author="demo"><createTable tableName="DEMO_T_"><column name="A_" type="int"/></createTable></changeSet>
                <changeSet id="2" author="demo">
                  <preConditions onFail="MARK_RAN"><not><tableExists tableName="DEMO_T_"/></not></preConditions>
                  <createTable tableName="DEMO_T_"><column name="STALE_" type="int"/></createTable>
                </changeSet>
                <changeSet id="3" author="demo">
                  <preConditions onFail="MARK_RAN"><tableExists tableName="ACT_RU_VARIABLE"/></preConditions>
                  <addColumn tableName="DEMO_T_"><column name="B_" type="int"/></addColumn>
                </changeSet>"""),
        )
        // no changelog here creates ACT_RU_VARIABLE: the engine does, so its existence is not known to be false
        assertEquals(listOf("1" to "ran", "2" to "skipped", "3" to "ran"), r.outcomes("db/a.xml"))
        assertEquals(listOf("A_", "B_"), r.columns("DEMO_T_"))
    }

    @Test
    fun neitherARollbackNorACommentedOutChangeSetRuns() {
        val r = replay(
            "db/a.xml" to changelog("""
                <changeSet id="1" author="demo">
                  <createTable tableName="DEMO_T_"><column name="A_" type="int"/></createTable>
                  <rollback><dropTable tableName="DEMO_T_"/></rollback>
                </changeSet>
                <!-- <changeSet id="2" author="demo"><addColumn tableName="DEMO_T_"><column name="GONE_" type="int"/></addColumn></changeSet> -->"""),
        )
        assertEquals(listOf("A_"), r.columns("DEMO_T_"))
    }

    @Test
    fun includeAllRunsInLiquibasesPathOrderNotANaturalOne() {
        // Liquibase sorts an includeAll by the plain path string: `v10/` runs before `v2/`, so the drop in
        // v10 finds no table yet and the column v2 creates is still there at the end
        val r = replay(
            master,
            "src/main/resources/db/changelog/versions/v2/a.xml" to changelog("""
                <changeSet id="1" author="demo"><createTable tableName="DEMO_T_"><column name="A_" type="int"/><column name="B_" type="int"/></createTable></changeSet>"""),
            "src/main/resources/db/changelog/versions/v10/b.xml" to changelog("""
                <changeSet id="2" author="demo"><dropColumn tableName="DEMO_T_" columnName="B_"/></changeSet>"""),
        )
        assertEquals(listOf("A_", "B_"), r.columns("DEMO_T_"))
    }

    @Test
    fun anIncludeRelativeToItsChangelogFindsTheSiblingNotAnyFileOfThatName() {
        val r = replay(
            "src/main/resources/db/master.xml" to changelog("""<include file="v3/customer.xml" relativeToChangelogFile="true"/>"""),
            "src/main/resources/db/v2/customer.xml" to changelog("""
                <changeSet id="1" author="demo"><createTable tableName="DEMO_OLD_"><column name="A_" type="int"/></createTable></changeSet>"""),
            "src/main/resources/db/v3/customer.xml" to changelog("""
                <changeSet id="1" author="demo"><createTable tableName="DEMO_NEW_"><column name="A_" type="int"/></createTable></changeSet>"""),
        )
        val run = r.runs.single { it.kind == "application" }
        assertEquals("the master runs v3 first, v2 runs after it on its own",
            listOf("src/main/resources/db/master.xml", "src/main/resources/db/v3/customer.xml", "src/main/resources/db/v2/customer.xml"),
            run.files.map { it.rel })
    }

    @Test
    fun propertiesAreExpandedInTypes() {
        val r = replay(
            "db/a.xml" to changelog("""
                <property name="varchar.type" value="nvarchar" dbms="mssql"/>
                <property name="varchar.type" value="varchar"/>
                <changeSet id="1" author="demo"><createTable tableName="DEMO_T_"><column name="A_" type="${'$'}{varchar.type}(255)"/></createTable></changeSet>"""),
        )
        assertEquals("varchar(255)", r.table("DEMO_T_")!!.columns.getValue("A_").type)
    }

    @Test
    fun copiesOfOneDefinitionAreOneHistory() {
        val v2 = changelog("""<changeSet id="1" author="demo"><createTable tableName="DEMO_T_"><column name="A_" type="int"/></createTable></changeSet>""", "DEMO-L1")
        val v3 = changelog("""<changeSet id="1" author="demo"><createTable tableName="DEMO_T_"><column name="A_" type="int"/></createTable></changeSet>
            <changeSet id="2" author="demo"><addColumn tableName="DEMO_T_"><column name="B_" type="int"/></addColumn></changeSet>""", "DEMO-L1")
        val r = replay("apps/v2/Demo.zip!liquibase-DEMO-L1.data.changelog.xml" to v2, "apps/v3/Demo.zip!liquibase-DEMO-L1.data.changelog.xml" to v3)
        val run = r.runs.single()
        assertEquals("definition", run.kind)
        assertEquals("DEMO-L1", run.key)
        assertEquals(listOf("A_", "B_"), run.schema.table("DEMO_T_")!!.columns.values.map { it.name })
    }

    @Test
    fun aPlainSqlFileAnIncludeAllReachesRunsItsDdl() {
        val r = replay(
            master,
            sql = mapOf("src/main/resources/db/changelog/versions/001-init.sql" to """
                CREATE TABLE demo_invoice_ (
                  id_ VARCHAR(64) NOT NULL PRIMARY KEY,
                  amount_ NUMERIC(10, 2) DEFAULT 0,
                  CONSTRAINT demo_invoice_pk UNIQUE (id_)
                );
                -- a comment with ALTER TABLE demo_invoice_ DROP COLUMN amount_;
                ALTER TABLE demo_invoice_ ADD COLUMN due_ TIMESTAMP WITH TIME ZONE;"""),
        )
        assertEquals(listOf("id_", "amount_", "due_"), r.columns("DEMO_INVOICE_"))
        assertEquals("NUMERIC(10,2)", r.table("DEMO_INVOICE_")!!.columns.getValue("AMOUNT_").type)
    }
}

/** The DDL a `<sql>` change, a `<sqlFile>` or a formatted-SQL changelog runs, read into schema changes. */
class SqlDdlTest {

    @Test
    fun theDialectsFlowableRunsOnAreRead() {
        val changes = SqlDdl.parse("""
            ALTER TABLE demo_t_ ADD a_ INT, b_ VARCHAR(10);
            ALTER TABLE demo_t_ DROP COLUMN IF EXISTS c_;
            ALTER TABLE demo_t_ RENAME COLUMN d_ TO e_;
            ALTER TABLE demo_t_ ALTER COLUMN f_ TYPE BIGINT;
            ALTER TABLE demo_t_ MODIFY (g_ VARCHAR2(20));
            ALTER TABLE demo_t_ ADD CONSTRAINT demo_fk FOREIGN KEY (a_) REFERENCES demo_o_ (id_);
            EXEC sp_rename 'demo_t_.h_', 'i_', 'COLUMN';
            DROP TABLE IF EXISTS demo_old_;
            INSERT INTO demo_t_ (a_) VALUES (1);
            """)
        assertEquals(listOf(
            // MSSQL: the second definition carries no ADD of its own
            LbChange.AddColumn("demo_t_", listOf(LbColumn("a_", "INT"))),
            LbChange.AddColumn("demo_t_", listOf(LbColumn("b_", "VARCHAR(10)"))),
            LbChange.DropColumn("demo_t_", listOf("c_")),
            LbChange.RenameColumn("demo_t_", "d_", "e_", null),
            LbChange.ModifyDataType("demo_t_", "f_", "BIGINT"),
            LbChange.ModifyDataType("demo_t_", "g_", "VARCHAR2(20)"),
            LbChange.RenameColumn("demo_t_", "h_", "i_", null),
            LbChange.DropTable("demo_old_"),
        ), changes)
    }

    @Test
    fun aFormattedSqlChangelogKeepsItsChangeSetsAndSkipsRollbackLines() {
        val cl = LiquibaseParser.parse("""
            --liquibase formatted sql

            --changeset demo:1
            CREATE TABLE demo_t_ (id_ INT);
            --rollback DROP TABLE demo_t_;

            --changeset demo:2
            --preconditions onFail:MARK_RAN
            --precondition-table-exists table:demo_t_
            ALTER TABLE demo_t_ ADD name_ VARCHAR(20);
            """.trimIndent(), "db/a.sql")
        assertNotNull(cl)
        val sets = cl!!.changeSets
        assertEquals(listOf("1", "2"), sets.map { it.id })
        assertEquals(listOf(LbChange.CreateTable("demo_t_", listOf(LbColumn("id_", "INT")))), sets[0].changes)
        assertEquals(LbPreconditions("MARK_RAN", listOf(LbPrecondition.TableExists("demo_t_"))), sets[1].preconditions)
        assertEquals(3, sets[0].line)
    }

    @Test
    fun aPlainSqlScriptIsNoChangelog() {
        assertNull(LiquibaseParser.parse("CREATE TABLE demo_t_ (id_ INT);", "db/a.sql"))
        assertTrue(LiquibaseParser.isChangelog("--liquibase formatted sql\n--changeset demo:1\nSELECT 1;"))
    }
}
