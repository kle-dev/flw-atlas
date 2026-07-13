package com.flowable.atlas.explorer

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * User-facing outcome reporting for the Atlas explorer: a balloon offering to open the freshly
 * generated page in the external browser or inside the IDE (embedded JCEF viewer, see
 * [AtlasFileEditorProvider]). The page is written into the project, so it can also be reopened
 * from the Project view at any time. Titles follow the notification convention: sentence case, no
 * "Flowable Atlas" prefix — the notification group already carries it.
 */
object AtlasExplorerNotifier {

    private const val GROUP_ID = "Flowable Atlas"
    private const val TITLE_EXPLORER_GENERATED = "Atlas explorer generated"
    private const val TITLE_ARTIFACTS_GENERATED = "Atlas artifacts generated"
    private const val TITLE_GENERATION_FAILED = "Atlas generation failed"

    /** Logs longer than this open as an editor tab instead of an info dialog. */
    private const val INLINE_LOG_LIMIT = 2000

    /**
     * @param explorerHtml the generated `.explorer.html` to open, or null if none was produced
     * @param explorerFile the same file as a VirtualFile (for the embedded viewer), if resolved
     * @param written      every file produced (1 for explorer-only, up to 5 artifacts)
     * @param quiet        suppress the balloon — used by the editor-toolbar Regenerate, where the
     *                     reloading browser is the feedback (the Atlas Hub still updates via events)
     */
    fun notifySuccess(
        project: Project,
        explorerHtml: Path?,
        explorerFile: VirtualFile?,
        written: List<Path>,
        quiet: Boolean = false,
    ) {
        if (quiet) return
        val title = if (written.size <= 1) TITLE_EXPLORER_GENERATED
        else "$TITLE_ARTIFACTS_GENERATED (${written.size} files)"
        val body = written.joinToString("<br>") { it.fileName.toString() }

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, body, NotificationType.INFORMATION)

        if (explorerHtml != null) {
            notification.addAction(NotificationAction.createSimple("Open in browser") {
                BrowserUtil.browse(explorerHtml.toFile())
            })
            if (explorerFile != null) {
                notification.addAction(NotificationAction.createSimple("Open in IDE") {
                    AtlasExplorerOpener.openInIde(project, explorerFile)
                })
            }
        }
        notification.notify(project)
    }

    fun notifyFailure(project: Project, message: String, log: String) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(TITLE_GENERATION_FAILED, message, NotificationType.ERROR)

        when {
            log.isBlank() -> {}
            log.length <= INLINE_LOG_LIMIT ->
                notification.addAction(NotificationAction.createSimple("Show details") {
                    Messages.showInfoMessage(project, log, "Generator Output")
                })
            else ->
                notification.addAction(NotificationAction.createSimple("Open log") {
                    openLogInEditor(project, log)
                })
        }
        notification.notify(project)
    }

    /** Writes the full [log] to a temp file and opens it as an editor tab (no truncation). */
    private fun openLogInEditor(project: Project, log: String) {
        val path = Files.createTempFile("flowable-atlas-generation-", ".log")
        Files.writeString(path, log)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            ?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }
}
