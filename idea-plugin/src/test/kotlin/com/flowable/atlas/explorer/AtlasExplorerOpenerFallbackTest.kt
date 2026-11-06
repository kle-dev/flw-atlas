package com.flowable.atlas.explorer

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Without the embedded browser, every way into the explorer — the Open action, the auto-open after
 * Generate, the intention, Atlas Findings — goes to the external browser, on the route that was asked
 * for. It used to open the generated page's multi-megabyte source in the HTML text editor.
 */
class AtlasExplorerOpenerFallbackTest : BasePlatformTestCase() {

    private val opened = ArrayList<Pair<Path, String?>>()
    private val notified = ArrayList<Notification>()
    private lateinit var dir: Path

    override fun setUp() {
        super.setUp()
        JcefSupport.overrideForTest = false
        AtlasBrowser.openForTest = { path, fragment -> opened.add(path to fragment) }
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    notified.add(notification)
                }
            },
        )
        dir = Files.createTempDirectory("atlas-opener")
    }

    override fun tearDown() {
        try {
            JcefSupport.overrideForTest = null
            AtlasBrowser.openForTest = null
            AtlasBrowser.canOpenFilesForTest = null
            dir.toFile().deleteRecursively()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** A real page on disk: the light fixture's `temp://` files are not in the local file system. */
    private fun page(): Pair<Path, VirtualFile> {
        val path = dir.resolve("DEMO.explorer.html")
        Files.writeString(path, "<!doctype html><title>DEMO</title>")
        return path to LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
    }

    fun testWithoutJcefThePageOpensInTheBrowserOnItsRoute() {
        AtlasBrowser.canOpenFilesForTest = true
        val (path, vf) = page()

        AtlasExplorerOpener.openInIde(project, vf, "process%3ADEMO-P001")

        assertFalse("no HTML source editor", FileEditorManager.getInstance(project).isFileOpen(vf))
        assertEquals(listOf(path to "process%3ADEMO-P001"), opened)
    }

    fun testWithNeitherABrowserNorJcefItSaysSo() {
        AtlasBrowser.canOpenFilesForTest = false
        val (_, vf) = page()

        AtlasExplorerOpener.openInIde(project, vf)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertFalse(FileEditorManager.getInstance(project).isFileOpen(vf))
        assertTrue(opened.isEmpty())
        assertEquals(listOf("Cannot open the explorer here"), notified.map { it.title })
    }

    fun testAPageWithNoPathOnDiskIsNotHandedToTheBrowser() {
        AtlasBrowser.canOpenFilesForTest = true
        val vf = myFixture.addFileToProject("DEMO.explorer.html", "<!doctype html>").virtualFile

        AtlasExplorerOpener.openInIde(project, vf)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(opened.isEmpty())
        assertEquals(listOf("Cannot open the explorer here"), notified.map { it.title })
    }
}
