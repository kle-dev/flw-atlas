package com.flowable.atlas

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelFiles
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files

/**
 * What drops the model index. The listener hears every file change in the IDE; it must hear the ones
 * that change this project's models — a whole folder included — and nothing else.
 */
class ModelIndexVfsListenerTest : BasePlatformTestCase() {

    private val service get() = project.service<FlowableModelIndexService>()

    fun testDeletingAFolderOfModelsDropsTheIndex() {
        val model = myFixture.addFileToProject("incoming/P.bpmn", """<definitions><process id="P"/></definitions>""")
        assertNotNull(service.refresh().find("P").firstOrNull())
        // One event, for the folder — not one per model inside it.
        WriteAction.run<Exception> { model.virtualFile.parent.delete(this) }
        assertNull("the folder's models are gone, the index must be too", service.cachedOrNull())
    }

    fun testAFolderInsideABuildDirectoryDoesNotDropTheIndex() {
        myFixture.addFileToProject("models/P.bpmn", """<definitions><process id="P"/></definitions>""")
        service.refresh()
        val build = myFixture.tempDirFixture.findOrCreateDir("build")
        WriteAction.run<Exception> { build.createChildDirectory(this, "classes") }
        assertNotNull("build output is not a model folder", service.cachedOrNull())
    }

    fun testAModelOutsideTheProjectDoesNotDropTheIndex() {
        myFixture.addFileToProject("models/P.bpmn", """<definitions><process id="P"/></definitions>""")
        service.refresh()
        val outside = Files.createTempDirectory("atlas-outside")
        try {
            VfsRootAccess.allowRootAccess(testRootDisposable, outside.toString())
            val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(outside)!!
            WriteAction.run<Exception> { dir.createChildData(this, "Downloaded.zip") }
            assertNotNull("a zip in another folder is not this project's model", service.cachedOrNull())
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    fun testExclusionIsJudgedBelowTheProjectFolder() {
        val base = project.basePath!!
        val excluded = ModelFiles.excluder(project)
        assertFalse(excluded("$base/models/P.bpmn"))
        assertTrue(excluded("$base/target/P.bpmn"))
        // The same project checked out below a folder that happens to be called "build".
        assertFalse(ModelFiles.isExcluded(ModelFiles.projectRelative(project, "$base/models/P.bpmn")!!))
    }
}
