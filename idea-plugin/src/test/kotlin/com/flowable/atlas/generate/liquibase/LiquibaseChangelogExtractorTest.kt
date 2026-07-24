package com.flowable.atlas.generate.liquibase

import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Detection of the Liquibase changelogs a Flowable Design app export ships — the read path that feeds the
 * "Generate → Liquibase" preview. Guards the regression where a changelog was only found when its parent
 * folder was literally `liquibase-models`: a real export keeps them flat at the ZIP root, and an unpacked
 * export leaves them loose in the tree. DEMO-* placeholder keys — this repo is public.
 */
class LiquibaseChangelogExtractorTest : BasePlatformTestCase() {

    private val changelogXml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<databaseChangeLog xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\">" +
            "<changeSet id=\"1\" author=\"t\"><createTable tableName=\"DB_DEMO\"/></changeSet></databaseChangeLog>\n"

    private var tempDir: Path? = null

    override fun tearDown() {
        try {
            super.tearDown()
        } finally {
            tempDir?.let { dir ->
                runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            }
        }
    }

    // ---- archive extraction -------------------------------------------------------------------

    fun testExtractFindsChangelogAtZipRoot() {
        val zip = appExportZip("DEMO-L1.data.changelog.xml" to changelogXml)
        val extracted = LiquibaseChangelogExtractor.extract(zip)
        assertEquals("the flat-root changelog is found", listOf("DEMO-L1"), extracted.map { it.key })
        assertEquals("its XML is emitted verbatim", changelogXml, extracted.single().xml)
    }

    fun testExtractFindsNestedLiquibaseModelsChangelog() {
        // The historical bundling path still works (backward compatibility) and the `liquibase-` prefix is stripped.
        val zip = appExportZip(
            "com/flowable/app/design/DemoApp/liquibase-models/liquibase-DEMO-L2.data.changelog.xml" to changelogXml,
        )
        assertEquals(listOf("DEMO-L2"), LiquibaseChangelogExtractor.extract(zip).map { it.key })
    }

    fun testExtractConfirmsByContent() {
        // A name that merely contains "changelog" but whose body is not a changelog must be dropped.
        val zip = appExportZip(
            "DEMO-OK.data.changelog.xml" to changelogXml,
            "not-a-changelog.xml" to "<somethingElse/>",
        )
        assertEquals("only the real changelog survives", listOf("DEMO-OK"), LiquibaseChangelogExtractor.extract(zip).map { it.key })
    }

    // ---- unpacked exports + output-dir guard (via computePlans) -------------------------------

    fun testComputePlansSurfacesLooseUnpackedChangelog() {
        val base = baseDir()
        myFixture.addFileToProject("proj/unpacked-export/DEMO-L3.data.changelog.xml", changelogXml)
        val apps = appsFor(base)
        assertTrue("a loose changelog of an unpacked export is surfaced: $apps", apps.contains("DEMO-L3"))
    }

    fun testComputePlansSkipsTheLiquibaseOutputDir() {
        val base = baseDir()
        myFixture.addFileToProject("proj/unpacked-export/DEMO-KEEP.data.changelog.xml", changelogXml)
        // A changelog living in the configured output folder is our own (master / already-generated) — never re-surfaced.
        myFixture.addFileToProject("proj/${FlowableAtlasProjectSettings.DEFAULT_LIQUIBASE_DIR}/DEMO-GEN.data.changelog.xml", changelogXml)
        val apps = appsFor(base)
        assertTrue("a genuine unpacked export appears: $apps", apps.contains("DEMO-KEEP"))
        assertFalse("a changelog inside the output dir is skipped: $apps", apps.contains("DEMO-GEN"))
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun baseDir(): VirtualFile = myFixture.addFileToProject("proj/.anchor", "").virtualFile.parent

    // On the EDT (where BasePlatformTestCase runs) read access is held implicitly — no explicit read action needed.
    private fun appsFor(base: VirtualFile): List<String> =
        project.service<LiquibaseScaffoldService>().computePlans(base).apps.map { it.key }

    /** A real on-disk ZIP (so [JarFileSystem] can mount it), refreshed into the VFS and returned. */
    private fun appExportZip(vararg entries: Pair<String, String>): VirtualFile {
        val dir = tempDir ?: Files.createTempDirectory("atlas-liquibase").also { tempDir = it }
        val zipPath = Files.createTempFile(dir, "app-export", ".zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            entries.forEach { (name, body) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(body.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(zipPath)
            ?: error("test zip not found in VFS: $zipPath")
    }
}
