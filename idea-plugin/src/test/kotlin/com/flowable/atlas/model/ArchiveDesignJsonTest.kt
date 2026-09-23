package com.flowable.atlas.model

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.settings.FlowableAtlasSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A Design app export keeps its forms, pages and actions only as `form-models/X.json`. The command line
 * reads them whatever the Design-workspace setting says; the IDE, with the setting at its default, did
 * not — no key, no preview, no outline for the export's forms. DEMO-* names: the repo is public.
 */
class ArchiveDesignJsonTest : BasePlatformTestCase() {

    private lateinit var dir: File
    private lateinit var root: VirtualFile

    override fun setUp() {
        super.setUp()
        dir = FileUtil.createTempDirectory("atlas-design-json", null)
        root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
        PsiTestUtil.addContentRoot(module, root)
        assertFalse("the default", FlowableAtlasSettings.getInstance().indexDesignWorkspace)
    }

    override fun tearDown() {
        try {
            PsiTestUtil.removeContentEntry(module, root)
            FileUtil.delete(dir)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testAFormKeptAsJsonInsideAnExportIsAForm() {
        val zip = archive()
        val jarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(zip)!!
        assertEquals(ModelType.FORM, ModelFiles.typeOf(jarRoot.findFileByRelativePath("form-models/DEMO-F001.json")!!))
        assertNull(
            "a process is read from its .bpmn beside the JSON",
            ModelFiles.typeOf(jarRoot.findFileByRelativePath("bpmn-models/DEMO-P001.json")!!),
        )
        assertNotNull(project.service<FlowableModelIndexService>().refresh().find("DEMO-F001").firstOrNull())
    }

    fun testALooseWorkspaceJsonStillWaitsForTheSetting() {
        File(dir, "form-models").mkdirs()
        File(dir, "form-models/DEMO-F002.json").writeText("""{"key":"DEMO-F002","name":"Demo"}""")
        val loose = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(dir, "form-models/DEMO-F002.json"))!!
        assertNull(ModelFiles.typeOf(loose))
    }

    private fun archive(): VirtualFile {
        val file = File(dir, "DEMO-app.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for ((path, content) in listOf(
                "bpmn-models/DEMO-P001.bpmn" to "<definitions><process id=\"DEMO-P001\"/></definitions>",
                "bpmn-models/DEMO-P001.json" to """{"key":"DEMO-P001","name":"Demo process"}""",
                "form-models/DEMO-F001.json" to """{"key":"DEMO-F001","name":"Demo form","rows":[]}""",
            )) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!
    }
}
