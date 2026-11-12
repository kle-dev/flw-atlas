package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A project that keeps its tables in its own Liquibase changelogs — run by the application at startup —
 * beside the app's schema definitions. The table a service maps is the one the application builds: the
 * definition the service model names is applied on request only, and the application's copy is usually
 * the newer one. Read the other way round, every column the project added was "not in Liquibase", and
 * the project's own changelog was "superseded" by its older copy in the app.
 */
class ApplicationChangelogTest {

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in entries) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun changelog(logical: String?, body: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"${logical?.let { " logicalFilePath=\"$it\"" } ?: ""}>
        $body
        </databaseChangeLog>"""

    private val createCustomer = """<changeSet id="1" author="demo"><createTable tableName="DEMO_CUSTOMER_">
        <column name="ID_" type="varchar(64)"/><column name="NAME_" type="varchar(255)"/><column name="FAX_" type="varchar(32)"/>
        </createTable></changeSet>"""

    private val service = """{"key":"DEMO-S1","name":"Customers","type":"database","tableName":"DEMO_CUSTOMER_",
        "referencedLiquibaseModelKey":"DEMO-D01Schema","lookupId":"id",
        "columnMappings":[{"name":"id","type":"STRING","columnName":"ID_"},{"name":"name","type":"STRING","columnName":"NAME_"},
          {"name":"email","type":"STRING","columnName":"EMAIL_"},{"name":"fax","type":"STRING","columnName":"FAX_"}],
        "operations":[{"key":"findById","type":"lookup","name":"Lookup"}]}"""

    private fun extract(files: Map<String, Any>): Map<String, Any?> {
        val dir = Files.createTempDirectory("atlas-app-changelog").toFile()
        try {
            for ((path, content) in files) {
                val f = File(dir, path).apply { parentFile.mkdirs() }
                if (content is ByteArray) f.writeBytes(content) else f.writeText(content as String)
            }
            return Atlas.extract(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** The project: a master including its data-object changelogs, and the app zip with the service and
     *  the definition it names — the older copy of the project's own changelog, under the same logical path. */
    private fun project(extra: Map<String, Any> = emptyMap()) = extract(mapOf(
        "src/main/resources/db/changelog/db.changelog-master.xml" to changelog(null, """<includeAll path="classpath*:db/changelog/data-objects"/>"""),
        "src/main/resources/db/changelog/data-objects/DEMO-L1-customer.xml" to changelog("DEMO-D01Schema", createCustomer + """
            <changeSet id="2" author="demo">
              <addColumn tableName="DEMO_CUSTOMER_"><column name="EMAIL_" type="varchar(255)"/></addColumn>
              <dropColumn tableName="DEMO_CUSTOMER_" columnName="FAX_"/>
            </changeSet>"""),
        "src/main/resources/apps/Demo-bar.zip" to zip(
            "DEMO.app" to """{"key":"DEMO","name":"Demo","flowApp":true}""",
            "service-DEMO-S1.service" to service,
            "liquibase-DEMO-D01Schema.data.changelog.xml" to changelog("DEMO-D01Schema", createCustomer),
        ),
    ) + extra)

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.list(k: String) = this[k] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.coverage() = list("services").single { it["key"] == "DEMO-S1" }["schemaCoverage"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.authority(key: String) = list("liquibase").single { it["key"] == key }["authority"] as Map<String, Any?>?

    /**
     * A table grown over several changelog files is listed under one of them. Every file that touches a
     * table used to carry all of its columns, so a table of four hundred columns shaped by eighty files was
     * listed eighty times — the section grew with files × columns, and was most of a real project's
     * graph.json. The others say where the list is.
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun aTableSeveralChangelogsShapeIsListedUnderTheOneThatCreatedIt() {
        val phone = "src/main/resources/db/changelog/data-objects/DEMO-L2-phone.xml"
        val r = extract(mapOf(
            "src/main/resources/db/changelog/db.changelog-master.xml" to changelog(null, """<includeAll path="classpath*:db/changelog/data-objects"/>"""),
            "src/main/resources/db/changelog/data-objects/DEMO-L1-customer.xml" to changelog(null, createCustomer),
            phone to changelog(null, """<changeSet id="1" author="demo">
                <addColumn tableName="DEMO_CUSTOMER_"><column name="PHONE_" type="varchar(32)"/></addColumn>
                <dropColumn tableName="DEMO_CUSTOMER_" columnName="FAX_"/></changeSet>"""),
        ))
        val created = r.list("liquibase").single { it["key"] == "DEMO-L1-customer" }
        val grown = r.list("liquibase").single { it["key"] == "DEMO-L2-phone" }
        val columns = created["columns"] as List<Map<String, Any?>>
        assertEquals(listOf("ID_", "NAME_", "PHONE_"), columns.map { it["name"] })
        // which file added a column stays on the column, and what the other file dropped stays with the table
        assertEquals(phone, (columns.last()["from"] as Map<String, Any?>)["file"])
        assertEquals(listOf("FAX_"), (created["dropped"] as List<Map<String, Any?>>).map { it["name"] })
        assertNull(created["columnsIn"])
        assertEquals(emptyList<Any?>(), grown["columns"])
        assertNull(grown["dropped"])
        assertEquals(mapOf("DEMO_CUSTOMER_" to "DEMO-L1-customer"), grown["columnsIn"])
        assertEquals("it still shapes the table", listOf("DEMO_CUSTOMER_"), grown["effectiveTables"])
    }

    /** The list sits where a reader goes for the table: under the changelog a service's coverage is read from. */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun theChangelogAServiceReadsItsTableFromKeepsTheColumns() {
        val r = extract(mapOf(
            "src/main/resources/db/changelog/db.changelog-master.xml" to changelog(null, """<includeAll path="classpath*:db/changelog/data-objects"/>"""),
            "src/main/resources/db/changelog/data-objects/DEMO-L1-customer.xml" to changelog(null, createCustomer),
            "src/main/resources/db/changelog/data-objects/DEMO-L2-phone.xml" to changelog(null, """<changeSet id="1" author="demo">
                <addColumn tableName="DEMO_CUSTOMER_"><column name="PHONE_" type="varchar(32)"/></addColumn></changeSet>"""),
            "services/customer.service" to service.replace("DEMO-D01Schema", "DEMO-L2-phone"),
        ))
        val created = r.list("liquibase").single { it["key"] == "DEMO-L1-customer" }
        val named = r.list("liquibase").single { it["key"] == "DEMO-L2-phone" }
        assertEquals(listOf("ID_", "NAME_", "FAX_", "PHONE_"), (named["columns"] as List<Map<String, Any?>>).map { it["name"] })
        assertTrue("the coverage and the columns it marks are on one page", named["coverage"] != null)
        assertEquals(mapOf("DEMO_CUSTOMER_" to "DEMO-L2-phone"), created["columnsIn"])
        assertEquals(emptyList<Any?>(), created["columns"])
    }

    @Test
    fun theServicesTableIsTheOneTheApplicationBuilds() {
        val r = project()
        val cov = r.coverage()
        assertEquals("DEMO-L1-customer", cov["liquibase"])
        @Suppress("UNCHECKED_CAST")
        val rows = (cov["rows"] as List<Map<String, Any?>>).associateBy { it["sql"] }
        assertEquals("EMAIL_ is in the table the application built", true, rows.getValue("EMAIL_")["inLiquibase"])
        // FAX_ is mapped, but a change set dropped it — and the row says which
        val fax = rows.getValue("FAX_")
        assertEquals("extra-service", fax["status"])
        @Suppress("UNCHECKED_CAST")
        val by = (fax["removed"] as Map<String, Any?>)["by"] as Map<String, Any?>
        assertEquals("src/main/resources/db/changelog/data-objects/DEMO-L1-customer.xml" to "2", by["file"] to by["changeSet"])
    }

    @Test
    fun theAppsCopyOfTheSameChangelogIsACopyNotARival() {
        val r = project()
        assertEquals("live", r.authority("DEMO-L1-customer")!!["status"])
        val def = r.authority("DEMO-D01Schema")!!
        assertEquals("copy", def["status"])
        assertEquals("DEMO-L1-customer", def["copyOf"])
        assertTrue(r.list("findings").none { it["check"] == "changelogIssues" })
    }

    @Test
    fun anAppCopyThatDiffersFromTheCodeIsNamedChangeSetByChangeSet() {
        val drift = project().list("findings").filter { it["check"] == "changelogDrift" }
        assertEquals(listOf("liquibase:DEMO-D01Schema"), drift.map { it["node"] })
        assertEquals(
            "`src/main/resources/db/changelog/data-objects/DEMO-L1-customer.xml` in the code does not match the app's copy " +
                "`Demo-bar.zip!liquibase-DEMO-D01Schema.data.changelog.xml`: change set `2` only in the code",
            drift.single()["message"],
        )
    }

    @Test
    fun anAppCopyWrittenOutAgainIsNoDifference() {
        // the same change sets, with other whitespace, another attribute order and a comment: Design wrote it out again
        val same = """<!-- exported --><changeSet author="demo" id="1">
            <createTable tableName="DEMO_CUSTOMER_"><column type="varchar(64)" name="ID_"/>
            <column name="NAME_"   type="varchar(255)"/>
            <column name="FAX_" type="varchar(32)"/></createTable></changeSet>
            <changeSet id="2" author="demo"><comment>email, no fax</comment>
              <addColumn tableName="DEMO_CUSTOMER_"><column name="EMAIL_" type="varchar(255)"/></addColumn>
              <dropColumn tableName="DEMO_CUSTOMER_" columnName="FAX_"/>
            </changeSet>"""
        val r = project(mapOf("src/main/resources/apps/Demo-bar.zip" to zip(
            "DEMO.app" to """{"key":"DEMO","name":"Demo","flowApp":true}""",
            "service-DEMO-S1.service" to service,
            "liquibase-DEMO-D01Schema.data.changelog.xml" to changelog("DEMO-D01Schema", same),
        )))
        assertEquals("copy", r.authority("DEMO-D01Schema")!!["status"])
        assertTrue(r.list("findings").none { it["check"] == "changelogDrift" })
    }

    @Test
    fun anOlderDefinitionOfTheTableUnderAnotherNameIsSuperseded() {
        val r = project(mapOf("src/main/resources/apps/Old-bar.zip" to zip(
            "liquibase-DEMO-D01.data.changelog.xml" to changelog("DEMO-D01", createCustomer),
        )))
        assertEquals("superseded", r.authority("DEMO-D01")!!["status"])
        assertEquals(listOf("DEMO-L1-customer"), r.authority("DEMO-D01")!!["supersededBy"])
        assertEquals(listOf("superseded by DEMO-L1-customer"),
            r.list("findings").filter { it["check"] == "changelogIssues" }.map { it["message"] })
    }

    @Test
    fun changelogsOfOneRunAreOneHistory() {
        // a third file of the same master adds a column to the table: part of it, never its rival
        val r = project(mapOf("src/main/resources/db/changelog/data-objects/DEMO-L2-customer-phone.xml" to changelog(null, """
            <changeSet id="1" author="demo"><addColumn tableName="DEMO_CUSTOMER_"><column name="PHONE_" type="varchar(32)"/></addColumn></changeSet>""")))
        assertEquals("live", r.authority("DEMO-L2-customer-phone")!!["status"])
        @Suppress("UNCHECKED_CAST")
        val rows = r.coverage()["rows"] as List<Map<String, Any?>>
        assertEquals("no-service", rows.single { it["sql"] == "PHONE_" }["status"])
        assertTrue(r.list("findings").none { it["check"] == "changelogIssues" })
    }

    @Test
    fun theApplicationsOwnTableIsNoOrphanButAChangelogNamingAMissingServiceIs() {
        val r = project(mapOf(
            "src/main/resources/db/changelog/data-objects/DEMO-L3-lock.xml" to changelog(null, """
                <changeSet id="1" author="demo"><createTable tableName="DEMO_LOCK_"><column name="ID_" type="int"/></createTable></changeSet>"""),
            "src/main/resources/db/changelog/data-objects/DEMO-L4-audit.xml" to changelog(null, """
                <property name="serviceDefinitionReferences" value="DEMO-S9"/>
                <changeSet id="1" author="demo"><createTable tableName="DEMO_AUDIT_"><column name="ID_" type="int"/></createTable></changeSet>"""),
        ))
        assertNull(r.authority("DEMO-L3-lock"))
        assertEquals("orphan", r.authority("DEMO-L4-audit")!!["status"])
        assertEquals(
            listOf("changelog names service `DEMO-S9`, which the project does not define, and no service or data object references it"),
            r.list("findings").filter { it["check"] == "changelogIssues" }.map { it["message"] },
        )
    }

    @Test
    fun aRepositoryWithoutServiceModelsDoesNotJudgeTheServicesItsChangelogsName() {
        // the models are deployed from elsewhere: the service a changelog names is simply not here
        val r = extract(mapOf(
            "src/main/resources/db/changelog/data-objects/DEMO-L4-audit.xml" to changelog(null, """
                <property name="serviceDefinitionReferences" value="DEMO-S9"/>
                <changeSet id="1" author="demo"><createTable tableName="DEMO_AUDIT_"><column name="ID_" type="int"/></createTable></changeSet>"""),
        ))
        assertNull(r.authority("DEMO-L4-audit"))
        assertTrue(r.list("findings").none { it["check"] == "changelogIssues" })
    }

    @Test
    fun twoFilesOfOneNameInVersionFoldersAreTwoChangelogs() {
        val r = extract(mapOf(
            "src/main/resources/db/changelog/db.changelog-master.xml" to changelog(null, """<includeAll path="db/changelog/versions/"/>"""),
            "src/main/resources/db/changelog/versions/v2/customer.xml" to changelog(null, createCustomer),
            "src/main/resources/db/changelog/versions/v3/customer.xml" to changelog(null, """
                <changeSet id="2" author="demo"><addColumn tableName="DEMO_CUSTOMER_"><column name="EMAIL_" type="varchar(255)"/></addColumn></changeSet>"""),
        ))
        val lbs = r.list("liquibase").filter { it["file"].toString().contains("versions/") }
        assertEquals(listOf("v2/customer", "v3/customer"), lbs.map { it["key"] })
        assertEquals(listOf("v2/customer.xml", "v3/customer.xml"), lbs.map { it["label"] })
        @Suppress("UNCHECKED_CAST")
        val cols = (lbs[0]["columns"] as List<Map<String, Any?>>).map { it["name"] }
        assertEquals("v2's page shows the table as v3 left it", listOf("ID_", "NAME_", "FAX_", "EMAIL_"), cols)
    }

    @Test
    fun everyExportOfOneDefinitionIsOneNodeWithTheNewestRevision() {
        val v2 = changelog("DEMO-L9", """<changeSet id="1" author="demo"><createTable tableName="DEMO_T_"><column name="A_" type="int"/></createTable></changeSet>""")
        val v3 = changelog("DEMO-L9", """<changeSet id="1" author="demo"><createTable tableName="DEMO_T_"><column name="A_" type="int"/></createTable></changeSet>
            <changeSet id="2" author="demo"><addColumn tableName="DEMO_T_"><column name="B_" type="int"/></addColumn></changeSet>""")
        val r = extract(mapOf(
            "apps/v2/Demo.zip" to zip("liquibase-DEMO-L9.data.changelog.xml" to v2),
            "apps/v3/Demo.zip" to zip("liquibase-DEMO-L9.data.changelog.xml" to v3),
        ))
        val lb = r.list("liquibase").single()
        assertEquals("apps/v3/Demo.zip!liquibase-DEMO-L9.data.changelog.xml", lb["file"])
        assertEquals(2, (lb["revisions"] as List<*>).size)
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("A_", "B_"), (lb["columns"] as List<Map<String, Any?>>).map { it["name"] })
    }
}
