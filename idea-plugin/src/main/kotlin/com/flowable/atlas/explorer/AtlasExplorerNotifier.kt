package com.flowable.atlas.explorer

import com.flowable.atlas.AtlasDetails
import com.flowable.atlas.AtlasNotifications
import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.action.FlowableActionIds
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * User-facing outcome reporting for the Atlas explorer: a balloon offering to open the freshly
 * generated page in the external browser or inside the IDE (embedded JCEF viewer, see
 * [AtlasFileEditorProvider]). The page is written into the project, so it can also be reopened
 * from the Project view at any time. Titles follow the notification convention: sentence case, no
 * "Flowable Atlas" prefix — the notification group already carries it.
 */
object AtlasExplorerNotifier {

    private const val TITLE_EXPLORER_GENERATED = "Atlas explorer generated"
    private const val TITLE_ARTIFACTS_GENERATED = "Atlas artifacts generated"
    private const val TITLE_GENERATION_FAILED = "Atlas generation failed"

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
        if (written.isEmpty()) {
            // Not a success: nothing was written. (The artifact setter keeps the selection non-empty,
            // so this is a guard, not a path anyone should reach.)
            AtlasNotifications.group()
                .createNotification(
                    "Nothing generated",
                    "No Atlas artifacts are selected — choose at least one in Settings → Tools → Flowable Atlas → Generation.",
                    NotificationType.WARNING,
                )
                .notify(project)
            return
        }
        val title = if (written.size <= 1) TITLE_EXPLORER_GENERATED
        else "$TITLE_ARTIFACTS_GENERATED (${written.size} files)"
        val body = written.joinToString("<br>") { it.fileName.toString() }

        val notification = AtlasNotifications.results()
            .createNotification(title, body, NotificationType.INFORMATION)

        if (explorerHtml != null) {
            if (AtlasBrowser.canOpenFiles()) {
                notification.addAction(NotificationAction.createSimple("Open in browser") {
                    AtlasBrowser.open(explorerHtml)
                })
            }
            if (explorerFile != null) {
                notification.addAction(NotificationAction.createSimple("Open in IDE") {
                    AtlasExplorerOpener.openInIde(project, explorerFile)
                })
            }
        }
        notification.notify(project)
    }

    fun notifyFailure(project: Project, message: String, log: String) {
        val notification = AtlasNotifications.group()
            .createNotification(TITLE_GENERATION_FAILED, message, NotificationType.ERROR)
        if (log.isNotBlank()) {
            notification.addAction(NotificationAction.createSimple(message("details.show")) {
                AtlasDetails.show(project, message("details.generation"), log)
            })
        }
        notification.notify(project)
    }

    /**
     * No page to open, with the action that makes one. It was a Yes/No dialog in two places (the Open
     * action, the Open-in-Explorer intention) and a balloon in a third; a question nobody has to answer
     * before they can go on is a balloon.
     */
    fun notifyNoExplorer(project: Project, outputDir: String) {
        AtlasNotifications.group()
            .createNotification(message("explorer.none.title"), message("explorer.none", outputDir), NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring(FlowableActionIds.text(FlowableActionIds.GENERATE_ATLAS_EXPLORER)) {
                val action = ActionManager.getInstance().getAction(FlowableActionIds.GENERATE_ATLAS_EXPLORER) ?: return@createSimpleExpiring
                ActionManager.getInstance().tryToExecute(action, null, null, null, true)
            })
            .notify(project)
    }
}
