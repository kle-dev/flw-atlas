package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A Design export ships its Liquibase changelogs inside the app zip, next to the models. Reading only
 * the changelogs on disk left every one of them invisible — the app's own reference to it was a
 * "missing model", and the service it describes had no schema coverage.
 */
class ArchiveChangelogTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        private fun zip(vararg entries: Pair<String, String>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { z ->
                for ((name, text) in entries) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            }
            return out.toByteArray()
        }

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-archive-changelog-test").toFile()
            File(dir, "DemoApp.zip").writeBytes(zip(
                "DEMO.app" to """{"key":"DEMO","name":"Demo","flowApp":true,
                    "extension":{"design":{"childModels":[{"key":"DEMO-S1","type":"service"},{"key":"DEMO-L1","type":"liquibase"}]}}}""",
                "service-DEMO-S1.service" to """{"key":"DEMO-S1","name":"Orders","type":"database","tableName":"DEMO_ORDER_",
                    "referencedLiquibaseModelKey":"DEMO-L1","lookupId":"id",
                    "columnMappings":[{"name":"id","type":"STRING","columnName":"ID_"},{"name":"total","type":"DOUBLE","columnName":"TOTAL_"}],
                    "operations":[{"key":"findById","type":"lookup","name":"Lookup"}]}""",
                // names the same changelog but a table it does not create: no gap of DEMO_ORDER_ is this service's
                "service-DEMO-S2.service" to """{"key":"DEMO-S2","name":"Other","type":"database","tableName":"DEMO_OTHER_",
                    "referencedLiquibaseModelKey":"DEMO-L1","lookupId":"id",
                    "columnMappings":[{"name":"id","type":"STRING","columnName":"ID_"}],"operations":[]}""",
                "liquibase-DEMO-L1.data.changelog.xml" to """<databaseChangeLog>
                    <changeSet id="1" author="demo">
                      <createTable tableName="DEMO_ORDER_">
                        <column name="ID_" type="VARCHAR(64)"/><column name="TOTAL_" type="NUMERIC"/><column name="NOTE_" type="VARCHAR(255)"/>
                      </createTable>
                    </changeSet>
                  </databaseChangeLog>""",
            ))
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun nodes(): List<Map<String, Any?>> =
        ((result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>)

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aChangelogInsideTheAppZipIsRead() {
        val lb = nodes().single { it["id"] == "liquibase:DEMO-L1" }
        assertEquals("DemoApp.zip!liquibase-DEMO-L1.data.changelog.xml", lb["file"])
        assertEquals("liquibase-DEMO-L1.data.changelog.xml", lb["label"])
        val svc = nodes().single { it["id"] == "service:DEMO-S1" }["data"] as Map<String, Any?>
        val coverage = svc["schemaCoverage"] as? Map<String, Any?>
        assertTrue("the service gets its schema coverage from the archived changelog", coverage != null)
        val gaps = (result["findings"] as List<Map<String, Any?>>).filter { it["check"] == "schemaGaps" }
        val unmapped = gaps.filter { it["message"].toString().contains("not mapped by the service") }.map { it["subject"] }
        assertEquals("NOTE_ is in Liquibase and mapped by nothing", listOf("DEMO_ORDER_.NOTE_"), unmapped)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun anotherTablesColumnsAreNotThisServicesGaps() {
        val gaps = (result["findings"] as List<Map<String, Any?>>).filter { it["check"] == "schemaGaps" && it["node"] == "service:DEMO-S2" }
        assertTrue("DEMO_ORDER_'s columns are not DEMO-S2's gaps: $gaps", gaps.none { it["subject"].toString().startsWith("DEMO_ORDER_") })
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun theAppsReferenceToItsChangelogIsNotAMissingModel() {
        val missing = (result["findings"] as List<Map<String, Any?>>).filter { it["check"] == "missingRefs" }
        assertTrue("$missing", missing.isEmpty())
    }
}
