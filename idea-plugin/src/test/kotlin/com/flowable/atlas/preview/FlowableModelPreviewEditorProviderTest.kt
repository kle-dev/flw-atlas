package com.flowable.atlas.preview

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Processes, cases, decisions, forms and pages open as text beside their picture — loose or out of an
 * archive; other models keep the plain editor. DEMO-* names: the repo is public.
 */
class FlowableModelPreviewEditorProviderTest : BasePlatformTestCase() {

    private val provider = FlowableModelPreviewEditorProvider()

    override fun tearDown() {
        try {
            val manager = FileEditorManager.getInstance(project)
            manager.openFiles.forEach(manager::closeFile)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun accepts(path: String, text: String = "{}") =
        provider.accept(project, myFixture.addFileToProject(path, text).virtualFile)

    fun testDrawableModelsAreTaken() {
        assertTrue(accepts("models/DEMO-P001.bpmn", "<definitions/>"))
        assertTrue(accepts("models/DEMO-C001.cmmn", "<definitions/>"))
        assertTrue(accepts("models/DEMO-D001.dmn", "<definitions/>"))
        assertTrue(accepts("models/DEMO-F001.form"))
        assertTrue(accepts("models/DEMO-PG001.page"))
    }

    fun testOtherFilesAreNot() {
        assertFalse(accepts("models/DEMO-A001.action"))
        assertFalse(accepts("models/DEMO.app"))
        assertFalse(accepts("README.md", "x"))
    }

    fun testAnArchiveEntryIsTaken() {
        val dir = FileUtil.createTempDirectory("atlas-preview", null)
        try {
            val zip = File(dir, "DEMO-app.zip")
            ZipOutputStream(zip.outputStream().buffered()).use {
                it.putNextEntry(ZipEntry("form-DEMO-F001.form"))
                it.write(FORM.toByteArray())
                it.closeEntry()
            }
            val local = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(zip)!!
            val entry = JarFileSystem.getInstance().getJarRootForLocalFile(local)!!.findChild("form-DEMO-F001.form")!!
            assertTrue(provider.accept(project, entry))
        } finally {
            FileUtil.delete(dir)
        }
    }

    fun testAFormOpensSplitWithItsWireframe() {
        // Built through the provider: the light fixture's editor manager opens plain text editors only.
        val file = myFixture.addFileToProject("models/DEMO-F002.form", FORM).virtualFile
        val editor = provider.createEditor(project, file) as FlowableModelPreviewEditorProvider.ModelEditor
        try {
            editor.component // the layout is settled when the editor's UI is built
            assertEquals(TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW, editor.getLayout())
            val preview = editor.previewEditor as FlowableModelPreview
            PlatformTestUtil.waitWithEventsDispatching("the wireframe never arrived", { preview.document != null }, 10)
            assertTrue(preview.document!!.size().width > 0)
        } finally {
            Disposer.dispose(editor)
        }
    }

    private companion object {
        const val FORM = """{"metadata":{"name":"Claim"},"rows":[{"cols":[{"id":"amount","type":"number","label":"Amount","size":12}]}]}"""
    }
}
