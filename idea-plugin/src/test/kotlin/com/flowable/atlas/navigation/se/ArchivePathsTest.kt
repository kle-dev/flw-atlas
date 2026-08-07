package com.flowable.atlas.navigation.se

import com.flowable.atlas.index.ArchiveModelScanner
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A model packed inside a `.bar`/`.zip` presents as `archive → path/inside.bpmn`, so a search result
 * says which archive it came from — the whole point of the tab, since the platform cannot see into
 * an archive at all. Uses a real zip on disk: `jar://` mounting only works on a local file.
 */
class ArchivePathsTest : BasePlatformTestCase() {

    private lateinit var tempDir: File

    override fun tearDown() {
        try {
            if (::tempDir.isInitialized) FileUtil.delete(tempDir)
        } finally {
            super.tearDown()
        }
    }

    fun testArchiveEntryShowsArchiveNameAndInnerPath() {
        val entries = scanArchive(
            "demo-app.zip",
            "processes/demo-invoice.bpmn" to "<definitions/>",
            "forms/demo-approve.form" to """{ "key": "DEMO-F001" }""",
        )
        val paths = entries.map { ArchivePaths.displayPath(it) }.sorted()
        assertEquals(
            listOf("demo-app.zip → forms/demo-approve.form", "demo-app.zip → processes/demo-invoice.bpmn"),
            paths,
        )
    }

    fun testLooseFileShowsItsPlainName() {
        val loose = myFixture.addFileToProject("models/demo-invoice.bpmn", "<definitions/>").virtualFile
        assertEquals("demo-invoice.bpmn", ArchivePaths.displayPath(loose))
    }

    /** Writes a real zip and returns the model entries the scanner mounts out of it. */
    private fun scanArchive(name: String, vararg entries: Pair<String, String>): List<VirtualFile> {
        tempDir = FileUtil.createTempDirectory("atlas-se-archive", null)
        val archive = File(tempDir, name)
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            for ((path, content) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        val archiveFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(archive)
        assertNotNull("archive is in the VFS", archiveFile)
        val found = mutableListOf<VirtualFile>()
        ArchiveModelScanner.scan(archiveFile!!) { _, _, _, entry -> found.add(entry) }
        return found
    }
}
