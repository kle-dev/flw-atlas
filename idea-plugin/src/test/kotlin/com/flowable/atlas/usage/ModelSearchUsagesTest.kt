package com.flowable.atlas.usage

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.io.FileUtil
import com.flowable.atlas.index.ArchiveModelScanner
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.util.Processor
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The half of *Find in Models…* that has no UI: which hits come out, and where each one sits.
 *
 * The offsets are the point. The Search Everywhere tab navigates by line and column because the text
 * scanner decodes UTF-8 while the `Document` uses the file's own charset; a `UsageInfo` has to carry an
 * offset instead, so every one of them is found again in `psiFile.text`. A test that only counted hits
 * would pass with every range off by the number of multi-byte characters ahead of it.
 */
class ModelSearchUsagesTest : BasePlatformTestCase() {

    private var tempDir: File? = null

    override fun tearDown() {
        try {
            tempDir?.let { FileUtil.delete(it) }
        } finally {
            super.tearDown()
        }
    }

    private fun hits(pattern: String): List<UsageInfo> {
        val out = ArrayList<Usage>()
        ModelSearchUsages.collect(project, pattern, EmptyProgressIndicator(), Processor { out.add(it); true })
        return out.filterIsInstance<UsageInfo2UsageAdapter>().mapNotNull { it.usageInfo }
    }

    /** The text each hit actually covers — the assertion that a wrong offset cannot survive. */
    private fun covered(infos: List<UsageInfo>): List<String> = infos.map { info ->
        val text = info.file!!.text
        text.substring(info.navigationOffset, info.navigationOffset + (info.rangeInElement?.length ?: 0))
    }

    fun testEveryOccurrenceIsItsOwnHit() {
        myFixture.addFileToProject(
            "processes/order.bpmn",
            """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <process id="DEMO-P001" name="Order">
                <userTask id="approve" flowable:formKey="DEMO-F001"/>
                <userTask id="approveAgain" flowable:formKey="DEMO-F001"/>
              </process>
            </definitions>
            """.trimIndent(),
        )
        val found = hits("DEMO-F001")
        assertEquals("both mentions, each its own row", 2, found.size)
        assertEquals(listOf("DEMO-F001", "DEMO-F001"), covered(found))
    }

    fun testAModelIsReportedAtItsKeyDeclarationNotAtTheTopOfTheFile() {
        myFixture.addFileToProject(
            "processes/order.bpmn",
            """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <process id="DEMO-P001" name="Order"/>
            </definitions>
            """.trimIndent(),
        )
        val found = hits("DEMO-P001")
        assertTrue("the model itself is reported", found.isNotEmpty())
        assertTrue("not line 1 of the file", found.all { it.navigationOffset > 0 })
        assertEquals(setOf("DEMO-P001"), covered(found).toSet())
        // The key declaration is a hit once: the model pass reports it, the text pass skips what the
        // model pass already reported.
        assertEquals("no duplicate row for the declaration itself", 1, found.size)
    }

    fun testAnElementIsReportedAtItsDeclaredId() {
        myFixture.addFileToProject(
            "processes/order.bpmn",
            """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <process id="DEMO-P001">
                <userTask id="approveInvoice" name="Approve"/>
              </process>
            </definitions>
            """.trimIndent(),
        )
        val found = hits("approveInvoice")
        assertEquals(1, found.size)
        assertEquals(listOf("approveInvoice"), covered(found))
    }

    fun testAMultiByteCharacterAheadOfTheMatchDoesNotShiftIt() {
        // Decoded as UTF-8 the ö is one character; as bytes it is two. A hit located by byte offset
        // would land one character late, and every hit after it later still.
        myFixture.addFileToProject(
            "forms/approve.form",
            """{ "key": "DEMO-F001", "label": "Prüfen — Angebot größer", "outcome": "DEMO-OK" }""",
        )
        val found = hits("DEMO-OK")
        assertEquals(1, found.size)
        assertEquals(listOf("DEMO-OK"), covered(found))
    }

    fun testAHitInsideAnArchiveBecomesAUsageOverThePackedText() {
        // The case the whole search exists for: the platform cannot see into a .bar/.zip at all. It is
        // driven one level down because a light fixture's project lives in memory and `jar://` mounts
        // only a file that is really on disk — so the archive is real and the project is not involved.
        val dir = FileUtil.createTempDirectory("atlas-find-archive", null).also { tempDir = it }
        val archive = File(dir, "demo-app.zip")
        val packed = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <process id="DEMO-PACKED" name="Prüfen — Packed"/>
              <process id="DEMO-PACKED-2" name="Second"/>
            </definitions>
        """.trimIndent()
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("processes/packed.bpmn"))
            zip.write(packed.toByteArray())
            zip.closeEntry()
        }
        val archiveFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(archive)
        assertNotNull("archive is in the VFS", archiveFile)

        val entries = mutableListOf<com.intellij.openapi.vfs.VirtualFile>()
        ArchiveModelScanner.scan(archiveFile!!, allowRefresh = true) { _, _, _, entry -> entries.add(entry) }
        val entry = entries.single()

        val psiFile = PsiManager.getInstance(project).findFile(entry)
        assertNotNull("an archive entry has PSI, which is what a usage needs", psiFile)
        val found = ModelSearchUsages.textUsages(psiFile!!, "DEMO-PACKED", emptySet())
        assertEquals("both processes, and the multi-byte name ahead of the second shifts neither", 2, found.size)
        assertEquals(listOf("DEMO-PACKED", "DEMO-PACKED"), covered(found))
    }

    fun testAPatternShorterThanTheFloorFindsNothing() {
        myFixture.addFileToProject("processes/order.bpmn", """<process id="a"/>""")
        assertTrue("one character matches most of every model's text", hits("a").isEmpty())
    }
}
