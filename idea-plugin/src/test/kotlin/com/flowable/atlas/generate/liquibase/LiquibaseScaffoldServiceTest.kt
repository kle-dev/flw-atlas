package com.flowable.atlas.generate.liquibase

import com.flowable.atlas.generate.liquibase.LiquibaseScaffoldService.ChangelogWrite
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The write pipeline of [LiquibaseScaffoldService]: from scratch it creates the master skeleton and
 * the include; against an existing master it only adds the missing include (nothing destructive); a
 * re-run is idempotent; "skip existing" keeps a file's content but still registers it. DEMO-*
 * placeholder keys — this repo is public.
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
}
