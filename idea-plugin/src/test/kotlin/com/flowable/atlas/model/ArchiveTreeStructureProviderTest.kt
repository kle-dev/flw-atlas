package com.flowable.atlas.model

import com.intellij.ide.highlighter.ArchiveFileType
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A `.zip` / `.bar` inside the project expands in the Project view into its entries. The platform alone
 * drops them (they are not "in content"). The second test pins that, so the day the platform stops doing
 * so this provider can go. DEMO-* names: the repo is public.
 */
class ArchiveTreeStructureProviderTest : BasePlatformTestCase() {

    private lateinit var dir: File
    private lateinit var root: VirtualFile

    override fun setUp() {
        super.setUp()
        dir = FileUtil.createTempDirectory("atlas-archive", null)
        root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
        // An archive in a project sits in a content root; the Project view lists only what is in the project.
        PsiTestUtil.addContentRoot(module, root)
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

    fun testABarIsAnArchive() {
        assertSame(ArchiveFileType.INSTANCE, FileTypeManager.getInstance().getFileTypeByFileName("DEMO-app.bar"))
    }

    fun testThePlatformAloneShowsNoEntries() {
        assertEmpty(platformNode(archive("DEMO-app.zip")).children)
    }

    fun testAZipExpandsIntoItsFoldersAndModels() {
        val children = expanded(archive("DEMO-app.zip"))
        assertEquals(listOf("DEMO-P001.bpmn", "form-models"), children.map(::fileName).sorted())
        val folder = children.single { fileName(it) == "form-models" }
        assertEquals(listOf("DEMO-F001.form"), folder.children.map(::fileName))
    }

    fun testABarExpandsToo() {
        assertEquals(2, expanded(archive("DEMO-app.bar")).size)
    }

    fun testANestedArchiveSaysItIsNotExpanded() {
        val file = File(dir, "DEMO-export.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("DEMO-app.bar")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
        }
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!
        val nested = expanded(vf).single() as ProjectViewNode<*>
        nested.update()
        assertTrue(nested.presentation.locationString.orEmpty().contains("not expanded"))
    }

    fun testOtherFilesAreLeftAlone() {
        File(dir, "README.md").writeText("x")
        val node = platformNode(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(dir, "README.md"))!!)
        assertSame(node, ArchiveTreeStructureProvider().modify(node, listOf(node), ViewSettings.DEFAULT).single())
    }

    private fun fileName(node: Any?): String = (node as ProjectViewNode<*>).virtualFile!!.name

    private fun expanded(archive: VirtualFile): Collection<AbstractTreeNode<*>> {
        val node = platformNode(archive)
        return ArchiveTreeStructureProvider().modify(node, listOf(node), ViewSettings.DEFAULT).single().children
    }

    private fun platformNode(file: VirtualFile) =
        PsiFileNode(project, PsiManager.getInstance(project).findFile(file)!!, ViewSettings.DEFAULT)

    private fun archive(name: String): VirtualFile {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for ((path, content) in listOf(
                "DEMO-P001.bpmn" to "<definitions><process id=\"DEMO-P001\"/></definitions>",
                "form-models/DEMO-F001.form" to """{"rows":[],"metadata":{"key":"DEMO-F001"}}""",
            )) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!
    }
}
