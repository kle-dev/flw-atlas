package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Changelog and template text is kept until the end of the walk, and nothing limited how much: a 1.3 MB
 * archive of forty 31 MB whitespace `.xml` entries ran a 1 GB heap out of memory. An XML file that is no
 * changelog is now dropped as soon as it is read, and what is kept counts against one budget per run.
 */
class RetainedTextBudgetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(file: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(file.outputStream()).use { z ->
            for ((name, text) in entries) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun maps(v: Any?): List<Map<String, Any?>> = (v as? List<*>).orEmpty().map { it as Map<String, Any?> }

    private fun skips(result: Map<String, Any?>) = maps(result["diagnostics"]).filter { it["kind"] == "skip" }.map { it["path"].toString() }

    private fun changelog(table: String, padding: Int = 0) =
        """<databaseChangeLog><changeSet id="1" author="demo"><createTable tableName="$table">
           <column name="ID_" type="VARCHAR(64)"/></createTable></changeSet></databaseChangeLog>""" + " ".repeat(padding)

    @Test
    fun aPlainSqlFileStillRunsAndAnXmlFileThatIsNoChangelogIsNotKept() {
        val root = tmp.newFolder("plain")
        zip(File(root, "DemoApp.zip"),
            "liquibase-DEMO-L1.data.changelog.xml" to """<databaseChangeLog><changeSet id="1" author="demo">
                <sqlFile path="DEMO-ddl.sql" relativeToChangelogFile="true"/></changeSet></databaseChangeLog>""",
            "DEMO-ddl.sql" to "CREATE TABLE DEMO_T_ (ID_ VARCHAR(64));",
            "DEMO-notes.xml" to "<notes><note>not a changelog</note></notes>",
        )
        val result = Atlas.extract(root)
        val changeSet = maps(maps(result["liquibase"]).single()["changeSets"]).single()
        // a plain .sql the replay could not find would be noted as "not in the project"
        assertEquals(changeSet.toString(), null, changeSet["note"])
        assertEquals("ran", changeSet["status"])
        assertEquals(emptyList<String>(), skips(result))
    }

    @Test
    fun whatGoesPastTheBudgetIsSaidAndNotKept() {
        val root = tmp.newFolder("budget")
        val pad = 600 * 1024
        zip(File(root, "DemoApp.zip"),
            "liquibase-DEMO-A.data.changelog.xml" to changelog("DEMO_A_", pad),
            "liquibase-DEMO-B.data.changelog.xml" to changelog("DEMO_B_", pad),
            "liquibase-DEMO-C.data.changelog.xml" to changelog("DEMO_C_", pad),
            // a megabyte of whitespace that is no changelog takes nothing from the budget
            "DEMO-blank.xml" to " ".repeat(2 * 1024 * 1024),
        )
        val result = Atlas.extract(root, retainedTextBudget = 1L shl 20)
        assertEquals(listOf("DemoApp.zip!liquibase-DEMO-B.data.changelog.xml", "DemoApp.zip!liquibase-DEMO-C.data.changelog.xml"),
            skips(result))
        assertTrue(maps(result["diagnostics"]).filter { it["kind"] == "skip" }.all { it["message"].toString().contains("used up") })
        assertEquals(listOf("liquibase-DEMO-A.data.changelog.xml"), maps(result["liquibase"]).map { it["file"].toString().substringAfter('!') })
    }
}
