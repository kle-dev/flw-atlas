package com.flowable.atlas.generate.liquibase

import com.flowable.atlas.generate.liquibase.LiquibaseScaffoldService.GeneratedChangelog
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The write pipeline of [LiquibaseScaffoldService]: from scratch it creates the master skeleton and
 * the include; against an existing master it only adds the missing include (nothing destructive); a
 * re-run is idempotent. DEMO-* placeholder keys — this repo is public.
 */
class LiquibaseScaffoldServiceTest : BasePlatformTestCase() {

    private val changelogXml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<databaseChangeLog><changeSet id=\"1\" author=\"t\"/></databaseChangeLog>\n"

    private val liquibaseDir = LiquibaseScaffoldService.LIQUIBASE_DIR
    private val masterRel = "$liquibaseDir/${LiquibaseChangelogGenerator.MASTER_CHANGELOG}"

    private fun baseDir(): VirtualFile = myFixture.addFileToProject("proj/.anchor", "").virtualFile.parent

    private fun read(dir: VirtualFile, rel: String): String? =
        dir.findFileByRelativePath(rel)?.let { VfsUtilCore.loadText(it) }

    private fun occurrences(haystack: String, needle: String): Int = haystack.split(needle).size - 1

    fun testFromScratchCreatesMasterAndInclude() {
        val base = baseDir()
        val written = project.service<LiquibaseScaffoldService>()
            .writeChangelogs(base, listOf(GeneratedChangelog("DEMO-DO-0001", changelogXml)))

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

        project.service<LiquibaseScaffoldService>()
            .writeChangelogs(base, listOf(GeneratedChangelog("DEMO-DO-0002", changelogXml)))

        val master = read(base, masterRel)!!
        assertTrue("existing content is preserved", master.contains("<!-- keep me -->"))
        assertTrue("the pre-existing include survives", master.contains("file=\"DEMO-EXISTING.changelog.xml\""))
        assertTrue("the new include is added", master.contains("file=\"DEMO-DO-0002.changelog.xml\""))
    }

    fun testReRunIsIdempotent() {
        val base = baseDir()
        val service = project.service<LiquibaseScaffoldService>()
        service.writeChangelogs(base, listOf(GeneratedChangelog("DEMO-DO-0003", changelogXml)))
        val afterFirst = read(base, masterRel)!!
        service.writeChangelogs(base, listOf(GeneratedChangelog("DEMO-DO-0003", changelogXml)))
        val afterSecond = read(base, masterRel)!!

        assertEquals("re-running must not rewrite the master", afterFirst, afterSecond)
        assertEquals("the include appears exactly once", 1, occurrences(afterSecond, "file=\"DEMO-DO-0003.changelog.xml\""))
    }
}
