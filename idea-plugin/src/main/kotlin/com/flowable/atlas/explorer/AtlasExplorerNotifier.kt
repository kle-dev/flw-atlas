package com.flowable.atlas.explorer

import com.flowable.atlas.settings.FlowableAtlasConfigurable
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * User-facing outcome reporting for the Atlas explorer: a balloon offering to open the freshly
 * generated page in the external browser or inside the IDE (embedded JCEF viewer, see
 * [AtlasFileEditorProvider]). The page is written into the project, so it can also be reopened
 * from the Project view at any time.
 */
object AtlasExplorerNotifier {

    private const val GROUP_ID = "Flowable Atlas"

    /**
     * @param explorerHtml the generated `.explorer.html` to open, or null if none was produced
     * @param explorerFile the same file as a VirtualFile (for the embedded viewer), if resolved
     * @param written      every file produced (1 for explorer-only, up to 5 for "all artifacts")
     */
    fun notifySuccess(project: Project, explorerHtml: Path?, explorerFile: VirtualFile?, written: List<Path>) {
        val title = if (written.size <= 1) "Flowable Atlas explorer generated"
        else "Flowable Atlas — ${written.size} artifacts generated"
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
                    FileEditorManager.getInstance(project).openFile(explorerFile, true)
                })
            }
        }
        notification.notify(project)
    }

    fun notifyFailure(project: Project, message: String, log: String) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification("Flowable Atlas generation failed", message, NotificationType.ERROR)

        notification.addAction(NotificationAction.createSimple("Configure Python…") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, FlowableAtlasConfigurable::class.java)
        })
        if (log.isNotBlank()) {
            notification.addAction(NotificationAction.createSimple("Show details") {
                Messages.showInfoMessage(project, log.take(4000), "Flowable Atlas — Generator Output")
            })
        }
        notification.notify(project)
    }
}
