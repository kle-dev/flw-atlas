package com.flowable.atlas.generate.liquibase

import com.flowable.atlas.generate.liquibase.LiquibaseScaffoldService.ChangelogWrite
import com.flowable.atlas.generate.liquibase.LiquibaseScaffoldService.Origin
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.Collections

/**
 * The write pipeline of [LiquibaseScaffoldService]: from scratch it creates the master skeleton and
 * the include; against an existing master it only adds the missing include (nothing destructive); a
 * re-run is idempotent; "skip existing" keeps a file's content but still registers it. And the plan:
 * a table is built from its service's columns, a table the project already creates is not offered, and
 * the preview's slow parts run outside the read lock. DEMO-* placeholder keys — this repo is public.
 */
class LiquibaseScaffoldServiceTest : BasePlatformTestCase() {

    private val changelogXml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<databaseChangeLog><changeSet id=\"1\" author=\"t\"/></databaseChangeLog>\n"

    private val liquibaseDir = FlowableAtlasProjectSettings.DEFAULT_LIQUIBASE_DIR
    private val masterRel = "$liquibaseDir/${LiquibaseChangelogGenerator.MASTER_CHANGELOG}"

    private fun baseDir(): VirtualFile = myFixture.addFileToProject("proj/.anchor", "").virtualFile.parent

    private fun read(dir: VirtualFile, rel: String): String? =
        dir.findFileByRelativePath(rel)?.let { VfsUtilCore.loadText(it) }

    private fun occurrences(haystack: String, needle: String): Int = haystack.split(needle).size - 1

    private fun write(base: VirtualFile, fileName: String, skipExisting: Boolean = false) =
        project.service<LiquibaseScaffoldService>()
            .writeResolved(base, liquibaseDir, listOf(ChangelogWrite(fileName, changelogXml)), skipExisting)

    fun testFromScratchCreatesMasterAndInclude() {
        val base = baseDir()
        val written = write(base, "DEMO-DO-0001.changelog.xml")

        assertEquals(1, written.size)
        assertEquals("DEMO-DO-0001.changelog.xml", written.first().name)
        assertEquals(changelogXml, read(base, "$liquibaseDir/DEMO-DO-0001.changelog.xml"))

        val master = read(base, masterRel)
        assertNotNull("the master skeleton is created from scratch", master)
        assertTrue("uses the 3.6 schema", master!!.contains("dbchangelog-3.6.xsd"))
        assertTrue(
            "the changelog is registered",
            master.contains("<include file=\"DEMO-DO-0001.changelog.xml\" relativeToChangelogFile=\"true\"/>"),
        )
    }

    fun testExistingMasterOnlyGetsTheMissingInclude() {
        val base = baseDir()
        val seeded = """
            <?xml version="1.0" encoding="UTF-8"?>
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                <!-- keep me -->
                <include file="DEMO-EXISTING.changelog.xml" relativeToChangelogFile="true"/>
            </databaseChangeLog>
        """.trimIndent()
        myFixture.addFileToProject("proj/$masterRel", seeded)

        write(base, "DEMO-DO-0002.changelog.xml")

        val master = read(base, masterRel)!!
        assertTrue("existing content is preserved", master.contains("<!-- keep me -->"))
        assertTrue("the pre-existing include survives", master.contains("file=\"DEMO-EXISTING.changelog.xml\""))
        assertTrue("the new include is added", master.contains("file=\"DEMO-DO-0002.changelog.xml\""))
    }

    fun testReRunIsIdempotent() {
        val base = baseDir()
        write(base, "DEMO-DO-0003.changelog.xml")
        val afterFirst = read(base, masterRel)!!
        write(base, "DEMO-DO-0003.changelog.xml")
        val afterSecond = read(base, masterRel)!!

        assertEquals("re-running must not rewrite the master", afterFirst, afterSecond)
        assertEquals("the include appears exactly once", 1, occurrences(afterSecond, "file=\"DEMO-DO-0003.changelog.xml\""))
    }

    fun testSkipExistingKeepsContentButStillRegisters() {
        val base = baseDir()
        val other = "<?xml version=\"1.0\"?>\n<databaseChangeLog><!-- hand-edited --></databaseChangeLog>\n"
        myFixture.addFileToProject("proj/$liquibaseDir/DEMO-DO-0004.changelog.xml", other)

        val written = write(base, "DEMO-DO-0004.changelog.xml", skipExisting = true)

        assertTrue("an existing file is not reported as written", written.isEmpty())
        assertEquals("its content is kept", other, read(base, "$liquibaseDir/DEMO-DO-0004.changelog.xml"))
        assertTrue(
            "but it is still registered in the master",
            read(base, masterRel)!!.contains("file=\"DEMO-DO-0004.changelog.xml\""),
        )
    }

    // ---- plan ---------------------------------------------------------------------------------

    /** The demo's shape: a data object over a database service whose columns are named apart from its fields. */
    private fun addOrderModels() {
        myFixture.addFileToProject("models/DEMO-orderService.service", """
            {"key":"DEMO-orderService","type":"database","tableName":"DEMO_ORDER","lookupId":"id",
             "columnMappings":[{"name":"id","columnName":"ID_","type":"string"},
                               {"name":"orderNumber","columnName":"ORDER_NO_","type":"string"},
                               {"name":"total","columnName":"TOTAL_","type":"double"}]}
        """.trimIndent())
        myFixture.addFileToProject("models/DEMO-orderDO.data", """
            {"key":"DEMO-orderDO","name":"Order","dataObjectType":"serviceRegistryDataObject",
             "referencedServiceDefinitionModelKey":"DEMO-orderService",
             "fieldMappings":[{"name":"id","type":"STRING"},{"name":"orderNumber","type":"STRING"},{"name":"total","type":"DOUBLE"}]}
        """.trimIndent())
    }

    private fun planned(key: String): LiquibaseScaffoldService.ChangelogPlanItem {
        project.service<FlowableModelIndexService>().refresh()
        val base = myFixture.tempDirFixture.findOrCreateDir(".")
        return project.service<LiquibaseScaffoldService>().computePlans(base).dataObjects.single { it.key == key }
    }

    fun testATableIsBuiltFromItsServicesColumns() {
        addOrderModels()
        val item = planned("DEMO-orderDO")
        assertEquals(Origin.FromService("DEMO-orderService", 3), item.origin)
        assertTrue(item.generatable)
        assertTrue("the service's column names: ${item.xml}", item.xml.contains("<column name=\"ORDER_NO_\""))
        assertTrue("keyed by the lookup id: ${item.xml}", item.xml.contains("primaryKey=\"true\""))
        assertEquals("DEMO_ORDER", item.tableName)
    }

    /** The project's own Liquibase creates the table: a second createTable would fail whichever runs second. */
    fun testATableTheProjectAlreadyCreatesIsNotWrittenAgain() {
        addOrderModels()
        myFixture.addFileToProject("src/main/resources/db/changelog/DEMO-002-order.xml", """
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                <changeSet id="1" author="demo">
                    <createTable tableName="demo_order"><column name="ID_" type="varchar(64)"/></createTable>
                </changeSet>
            </databaseChangeLog>
        """.trimIndent())
        val item = planned("DEMO-orderDO")
        val origin = item.origin as? Origin.AlreadyCreated
        assertNotNull("already created: ${item.origin}", origin)
        assertTrue(origin!!.file, origin.file.endsWith("src/main/resources/db/changelog/DEMO-002-order.xml"))
        assertFalse("nothing to write", item.generatable)
    }

    /** A changelog that only adds a column to the table does not create it: the table is still built. */
    fun testATableTheProjectOnlyAltersIsStillBuilt() {
        addOrderModels()
        myFixture.addFileToProject("src/main/resources/db/changelog/DEMO-003-order-note.xml", """
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                <changeSet id="1" author="demo">
                    <addColumn tableName="DEMO_ORDER"><column name="NOTE_" type="varchar(255)"/></addColumn>
                </changeSet>
            </databaseChangeLog>
        """.trimIndent())
        val item = planned("DEMO-orderDO")
        assertEquals(Origin.FromService("DEMO-orderService", 3), item.origin)
        assertTrue(item.generatable)
    }

    /**
     * What Atlas wrote to its output folder before may be overwritten, so it does not count — but it must not
     * hide the project's own changelog creating the same table, even when Atlas's file runs first.
     */
    fun testAnEarlierOutputDoesNotHideTheProjectsOwnTable() {
        addOrderModels()
        val table = """
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                <changeSet id="1" author="demo">
                    <createTable tableName="DEMO_ORDER"><column name="ID_" type="varchar(64)"/></createTable>
                </changeSet>
            </databaseChangeLog>
        """.trimIndent()
        val output = FlowableAtlasProjectSettings.getInstance(project).liquibaseOutputDir
        myFixture.addFileToProject("$output/DEMO-orderDO.changelog.xml", table)
        assertTrue("the output folder sorts first", output < "src/main/resources/schema")
        myFixture.addFileToProject("src/main/resources/schema/DEMO-002-order.xml", table)
        val origin = planned("DEMO-orderDO").origin as? Origin.AlreadyCreated
        assertNotNull(origin)
        assertTrue(origin!!.file, origin.file.endsWith("src/main/resources/schema/DEMO-002-order.xml"))
    }

    /** Atlas's own earlier output alone is what this run regenerates, not a table that exists. */
    fun testAnEarlierOutputAloneIsRegenerated() {
        addOrderModels()
        val output = FlowableAtlasProjectSettings.getInstance(project).liquibaseOutputDir
        myFixture.addFileToProject("$output/DEMO-orderDO.changelog.xml", """
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                <changeSet id="1" author="flowable-atlas">
                    <createTable tableName="DEMO_ORDER"><column name="ID_" type="varchar(255)"/></createTable>
                </changeSet>
            </databaseChangeLog>
        """.trimIndent())
        assertTrue(planned("DEMO-orderDO").generatable)
    }

    /** With no service behind it the columns can only be guessed from the fields — and the preview says so. */
    fun testWithoutAServiceTheColumnsAreGuessed() {
        myFixture.addFileToProject("models/DEMO-noteDO.data", """
            {"key":"DEMO-noteDO","name":"Note","dataObjectType":"serviceRegistryDataObject",
             "fieldMappings":[{"name":"text","type":"STRING"}]}
        """.trimIndent())
        val item = planned("DEMO-noteDO")
        assertEquals(Origin.Synthesized(1), item.origin)
        assertTrue(item.origin.label, item.origin.label.contains("guessed"))
    }

    /**
     * Generate → Liquibase is used right after a data object is saved — on a cold index. The scan used to
     * run inside the plan's read action, and the archives were read in it too: every keystroke waited.
     */
    fun testThePreviewScansAndReadsChangelogsWithoutTheReadLock() {
        addOrderModels()
        myFixture.addFileToProject(
            "unpacked-export/DEMO-L1.data.changelog.xml",
            "<databaseChangeLog><changeSet id=\"1\" author=\"t\"/></databaseChangeLog>",
        )
        val index = project.service<FlowableModelIndexService>()
        val scaffold = project.service<LiquibaseScaffoldService>()
        val base = myFixture.tempDirFixture.findOrCreateDir(".")
        val scanLocked = Collections.synchronizedList(ArrayList<Boolean>())
        val readLocked = Collections.synchronizedList(ArrayList<Boolean>())
        index.afterScanHook = { scanLocked.add(ApplicationManager.getApplication().isReadAccessAllowed) }
        scaffold.onBundledFile = { readLocked.add(ApplicationManager.getApplication().isReadAccessAllowed) }
        try {
            index.invalidate()
            val plans = PlatformTestUtil.waitForFuture(
                ApplicationManager.getApplication().executeOnPooledThread<LiquibaseScaffoldService.Plans> { scaffold.preparePlans(base) },
                30_000,
            )
            assertEquals("the index was built once for the preview, without the lock", listOf(false), scanLocked.toList())
            assertTrue("the changelog was read", readLocked.isNotEmpty())
            assertFalse("never under the read lock: $readLocked", true in readLocked)
            assertTrue(plans.dataObjects.any { it.key == "DEMO-orderDO" })
            assertTrue(plans.apps.any { it.key == "DEMO-L1" })
        } finally {
            index.afterScanHook = null
            scaffold.onBundledFile = null
        }
    }
}
