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

    fun testAClickOnAWireframeCellFindsTheField() {
        // a field called "name" is spelled like a JSON key long before its declaration
        val text = """{"name":"DEMO-F003","rows":[{"cols":[{"id":"name","type":"text","label":"Name","size":6},{"id":"amount","type":"number","size":6}]}]}"""
        val file = myFixture.addFileToProject("models/DEMO-F003.form", text).virtualFile
        val editor = provider.createEditor(project, file) as FlowableModelPreviewEditorProvider.ModelEditor
        try {
            editor.component
            val preview = editor.previewEditor as FlowableModelPreview
            PlatformTestUtil.waitWithEventsDispatching("the wireframe never arrived", { preview.document != null }, 10)
            val cell = com.flowable.atlas.diagram.FormSvgRenderer.picture(text.toByteArray())!!.hotspots.single { it.id == "name" }
            val offset = preview.offsetAt(java.awt.geom.Point2D.Double(cell.x + cell.width / 2, cell.y + cell.height / 2), text)!!
            assertEquals("name", text.substring(offset, offset + 4))
            assertEquals("the declaration, not the model's name key", "\"id\":\"", text.substring(offset - 6, offset))
        } finally {
            Disposer.dispose(editor)
        }
    }

    fun testASubformIsDrawnInsideItsFormAndADoubleClickOpensIt() {
        myFixture.addFileToProject("models/DEMO-F005.form",
            """{"metadata":{"key":"DEMO-F005","name":"Address"},"rows":[{"cols":[{"id":"street","type":"text","label":"Street","size":12}]}]}""")
        val text = """{"metadata":{"key":"DEMO-F004","name":"Claim"},"rows":[{"cols":[""" +
            """{"id":"addressSub","type":"subform","label":"Address","size":12,"extraSettings":{"formRef":"DEMO-F005"}}]}]}"""
        val file = myFixture.addFileToProject("models/DEMO-F004.form", text).virtualFile
        val editor = provider.createEditor(project, file) as FlowableModelPreviewEditorProvider.ModelEditor
        try {
            editor.component
            val preview = editor.previewEditor as FlowableModelPreview
            PlatformTestUtil.waitWithEventsDispatching("the wireframe never arrived", { preview.document != null }, 10)
            val picture = com.flowable.atlas.usage.DiagramSvgCache.getInstance(project)
                .resolvePicture(file, com.flowable.atlas.model.ModelType.FORM)!!
            assertTrue("the embedded form's field is drawn", picture.svg.contains("Street"))
            val sub = picture.hotspots.single { it.id == "addressSub" }
            val target = preview.openTarget(java.awt.geom.Point2D.Double(sub.x + sub.width / 2, sub.y + sub.height / 2))
            assertEquals("DEMO-F005.form", target?.file?.name)
            assertNull("a plain field opens nothing", preview.openTarget(java.awt.geom.Point2D.Double(1.0, 1.0)))
        } finally {
            Disposer.dispose(editor)
        }
    }

    /**
     * Typing breaks the model for a moment at most keystrokes. The picture stays as it last parsed, with a
     * line saying so, rather than flipping to "no layout" and back while someone types.
     */
    fun testAHalfTypedModelKeepsTheLastPicture() {
        val file = myFixture.addFileToProject("models/DEMO-F006.form", FORM).virtualFile
        val editor = provider.createEditor(project, file) as FlowableModelPreviewEditorProvider.ModelEditor
        try {
            editor.component
            val preview = editor.previewEditor as FlowableModelPreview
            PlatformTestUtil.waitWithEventsDispatching("the wireframe never arrived", { preview.document != null }, 10)
            val drawn = preview.document
            val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)!!
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                document.setText(FORM.dropLast(12))
            }
            PlatformTestUtil.waitWithEventsDispatching("the stale line never came up", { preview.showsStalePicture }, 10)
            assertSame("the last picture that parsed stays up", drawn, preview.document)
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { document.setText(FORM) }
            PlatformTestUtil.waitWithEventsDispatching("the stale line never went away", { !preview.showsStalePicture }, 10)
        } finally {
            Disposer.dispose(editor)
        }
    }

    /** The picture says what can be clicked before anyone clicks: an element under the pointer is outlined. */
    fun testHoveringAnElementOutlinesItAndNamesIt() {
        val file = myFixture.addFileToProject("models/DEMO-F007.form", FORM).virtualFile
        val editor = provider.createEditor(project, file) as FlowableModelPreviewEditorProvider.ModelEditor
        try {
            editor.component
            val preview = editor.previewEditor as FlowableModelPreview
            PlatformTestUtil.waitWithEventsDispatching("the wireframe never arrived", { preview.document != null }, 10)
            val cell = com.flowable.atlas.diagram.FormSvgRenderer.picture(FORM.toByteArray())!!.hotspots.single { it.id == "amount" }
            val hover = preview.hoverForTest(java.awt.geom.Point2D.Double(cell.x + cell.width / 2, cell.y + cell.height / 2))!!
            assertTrue(hover.tooltip, hover.tooltip.startsWith("amount"))
            assertEquals(cell.width, hover.bounds.width)
        } finally {
            Disposer.dispose(editor)
        }
    }

    fun testActualSizeIsAHundredPercent() {
        val canvas = SvgCanvas()
        canvas.actualSize()
        assertEquals(100, canvas.zoomPercent())
        canvas.zoomBy(1.25)
        assertEquals(125, canvas.zoomPercent())
    }

    private companion object {
        const val FORM = """{"metadata":{"name":"Claim"},"rows":[{"cols":[{"id":"amount","type":"number","label":"Amount","size":12}]}]}"""
    }
}
