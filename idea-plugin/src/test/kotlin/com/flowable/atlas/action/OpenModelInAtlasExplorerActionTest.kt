package com.flowable.atlas.action

import com.flowable.atlas.project.AtlasProjectRootService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * *Open in Atlas Explorer* on a model the index does not hold used to do nothing at all. It says why now:
 * the model is in another Flowable project than the one chosen in the Hub, or in a build folder.
 */
class OpenModelInAtlasExplorerActionTest : BasePlatformTestCase() {

    private val created = ArrayList<Path>()

    override fun tearDown() {
        try {
            AtlasProjectRootService.getInstance(project).setActiveSubProject("")
            created.forEach { it.toFile().deleteRecursively() }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** A model on disk under the project folder — the scope is a real sub-folder, which `temp://` files are not in. */
    private fun model(rel: String): VirtualFile {
        val base = Path.of(project.basePath!!)
        val top = base.resolve(rel.substringBefore('/'))
        if (top !in created) created.add(top)
        val path = base.resolve(rel)
        Files.createDirectories(path.parent)
        Files.writeString(path, """<definitions><process id="${path.fileName.toString().substringBefore('.')}"/></definitions>""")
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
    }

    fun testAModelInAnotherFlowableProjectSaysWhichOneIsActive() {
        model("DEMO-appA/models/DEMO-P1.bpmn20.xml")
        val other = model("DEMO-appB/models/DEMO-P2.bpmn20.xml")
        AtlasProjectRootService.getInstance(project).setActiveSubProject("DEMO-appA")

        val why = OpenModelInAtlasExplorerAction.whyNotInIndex(project, other)
        assertEquals("DEMO-P2.bpmn20.xml is outside DEMO-appA, the Flowable project Atlas is about — switch it in the Atlas Hub.", why)
    }

    fun testAModelInABuildFolderSaysAtlasDoesNotReadThere() {
        model("DEMO-appA/models/DEMO-P1.bpmn20.xml")
        val built = model("DEMO-appA/target/classes/DEMO-P3.bpmn20.xml")
        AtlasProjectRootService.getInstance(project).setActiveSubProject("DEMO-appA")

        assertEquals(
            "DEMO-P3.bpmn20.xml is in a build or dependency folder, which Atlas does not read.",
            OpenModelInAtlasExplorerAction.whyNotInIndex(project, built),
        )
    }

    fun testAModelInScopeWithoutAKeySaysSo() {
        val unreadable = model("DEMO-appA/models/DEMO-P4.bpmn20.xml")
        assertEquals(
            "Atlas could not read a model key from DEMO-P4.bpmn20.xml.",
            OpenModelInAtlasExplorerAction.whyNotInIndex(project, unreadable),
        )
    }
}
