package com.flowable.atlas.compare

import com.flowable.atlas.settings.FlowableAtlasSettings
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.contents.FileContent
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Comparing a model in the project against the same model inside an app export. Two things here were the
 * reason for the design, so both are pinned:
 *
 *  - a Design export consists of `.json` entries under `*-models` folders, which the plugin's `ModelFiles`
 *    only counts as models while *Index Flowable Design workspace* is on — and that is off by default.
 *    A comparison built on the model index would quietly offer the deployment `.form` and not the Design
 *    JSON beside it, which is the half the file in the project usually came from;
 *  - those entries are minified, so the comparison has to lay both sides out before showing them.
 *
 * A real zip on disk, because `jar://` only mounts a local file. DEMO-* keys — this repository is public.
 */
class ModelCompareTest : BasePlatformTestCase() {

    private val minifiedExport =
        """{"id":"FORM_MODEL-1","name":"Demo form","key":"DEMO-F001","editorJson":{"rows":[{"cols":[]}]}}"""

    private val generatedForm =
        "{\n  \"id\": \"FORM_MODEL-1\",\n  \"name\": \"Demo form, revised\",\n" +
            "  \"key\": \"DEMO-F001\",\n  \"editorJson\": {\n    \"rows\": [\n      {\n        \"cols\": []\n" +
            "      }\n    ]\n  }\n}\n"

    private var tempDir: File? = null
    private var archive: VirtualFile? = null

    override fun tearDown() {
        try {
            tempDir?.let { FileUtil.delete(it) }
        } finally {
            super.tearDown()
        }
    }

    fun testADesignExportsEntriesAreFoundWithoutTheDesignIndexSetting() {
        val settings = FlowableAtlasSettings.getInstance()
        val previous = settings.indexDesignWorkspace
        settings.indexDesignWorkspace = false
        try {
            assertEquals(
                "the Design JSON counts even with workspace indexing off; a README never counts",
                listOf("DEMO-F001.json", "DEMO-P031.bpmn", "form-DEMO-F001.form"),
                ArchiveEntryCandidates.modelEntriesOf(appExport()).map { it.name }.sorted(),
            )
        } finally {
            settings.indexDesignWorkspace = previous
        }
    }

    fun testTheReformattedComparisonIsLaidOutReadOnlyAndSaysSo() {
        val entry = exportEntry("form-models/DEMO-F001.json")
        val local = myFixture.addFileToProject("generated/DEMO-F001.json", generatedForm).virtualFile

        val prepared = ModelCompare.prepare(project, entry, local, reformat = true)
        assertTrue(prepared.reformatted)
        assertTrue(prepared.canReformat)
        assertEquals(true, prepared.request.getUserData(DiffUserDataKeys.FORCE_READ_ONLY))
        val titles = prepared.request.contentTitles.map { it.orEmpty() }
        assertEquals("Demo App.zip → form-models/DEMO-F001.json (reformatted)", titles.first())
        assertTrue("the project side names the file and its state: ${titles.last()}",
            titles.last().endsWith("DEMO-F001.json (reformatted)"))
        // The export is one line; that it no longer is, is the whole exercise.
        assertTrue(
            "the minified export is laid out",
            (prepared.request.contents.first() as DocumentContent).document.lineCount > 1,
        )
    }

    fun testTheRawComparisonShowsTheFilesThemselves() {
        val entry = exportEntry("form-models/DEMO-F001.json")
        val local = myFixture.addFileToProject("generated/DEMO-F001.json", generatedForm).virtualFile

        val prepared = ModelCompare.prepare(project, entry, local, reformat = false)
        assertFalse(prepared.reformatted)
        assertTrue("the toolbar still has the other view to offer", prepared.canReformat)
        assertNull(
            "nothing is forced read-only — the project side is the file, and editable",
            prepared.request.getUserData(DiffUserDataKeys.FORCE_READ_ONLY),
        )
        assertTrue(prepared.request.contentTitles.none { it.orEmpty().contains("reformatted") })
        assertEquals("the left side is the entry in the archive", entry, fileOf(prepared.request.contents.first()))
        assertEquals("the right side is the file in the project", local, fileOf(prepared.request.contents.last()))
    }

    fun testXmlModelsAreLeftAsTheyAre() {
        val entry = exportEntry("DEMO-P031.bpmn")
        val local = myFixture.addFileToProject("generated/DEMO-P031.bpmn", "<definitions/>").virtualFile

        val prepared = ModelCompare.prepare(project, entry, local, reformat = true)
        assertFalse("bpmn/cmmn/dmn are exported formatted already", prepared.reformatted)
        assertFalse("so there is nothing for the toolbar to switch to", prepared.canReformat)
    }

    private fun fileOf(content: DiffContent): VirtualFile? = when (content) {
        is DocumentContent -> content.highlightFile
        is FileContent -> content.file
        else -> null
    }

    /** One app export: the Design JSON, the deployment form beside it, a process, and one file that is neither. */
    private fun appExport(): VirtualFile = archive ?: run {
        val dir = FileUtil.createTempDirectory("atlas-compare", null).also { tempDir = it }
        val file = File(dir, "Demo App.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for ((path, content) in listOf(
                "form-models/DEMO-F001.json" to minifiedExport,
                "form-DEMO-F001.form" to """{"rows":[],"metadata":{"key":"DEMO-F001"}}""",
                "DEMO-P031.bpmn" to "<definitions>\n  <process id=\"DEMO-P031\"/>\n</definitions>\n",
                "README.md" to "not a model",
            )) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        val mounted = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        assertNotNull("the archive is in the VFS", mounted)
        mounted!!.also { archive = it }
    }

    private fun exportEntry(path: String): VirtualFile =
        ArchiveEntryCandidates.modelEntriesOf(appExport()).single { it.path.endsWith("!/$path") }
}
