package com.flowable.atlas.explorer

import com.flowable.atlas.AtlasNotifications
import com.flowable.atlas.FlowableAtlasBundle.message
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Opens a generated `*.explorer.html` (or `*.erd.html`) where it can be *seen*: inside the IDE, in its
 * embedded "Atlas Explorer" viewer tab ([AtlasFileEditor], contributed by [AtlasFileEditorProvider]),
 * when JCEF is available — else in the external browser — else a balloon saying neither is possible here.
 *
 * Every path into the explorer goes through here: the Hub's list, the Open action, the auto-open after
 * Generate, the balloon, the intention, Atlas Findings, Recent Models. The decision used to live in two of
 * those callers; the others opened the file regardless, and without JCEF the platform's HTML *text*
 * editor took it — several megabytes of generated source, with the route that was asked for dropped.
 */
object AtlasExplorerOpener {

    /** Opens [file], on the page [hash] names (a route without `#`, or null for the dashboard). */
    fun openInIde(project: Project, file: VirtualFile, hash: String? = null) {
        // A file inside an archive (or in a test's in-memory file system) has no path a browser could open.
        val onDisk = if (file.isInLocalFileSystem) file.fileSystem.getNioPath(file) else null
        when {
            JcefSupport.isAvailable() -> openInEditorTab(project, file, hash)
            onDisk != null && AtlasBrowser.canOpenFiles() -> AtlasBrowser.open(onDisk, hash)
            else -> notifyCannotOpen(project, file.name)
        }
    }

    /** The balloon for a page that neither the editor tab nor a browser can show here (Remote Dev without JCEF). */
    fun notifyCannotOpen(project: Project, fileName: String) {
        AtlasNotifications.group()
            .createNotification(
                message("hub.explorer.cannotOpen.title"),
                message("hub.explorer.cannotOpen", fileName),
                NotificationType.WARNING,
            )
            .notify(project)
    }

    private fun openInEditorTab(project: Project, file: VirtualFile, hash: String?) {
        val manager = FileEditorManager.getInstance(project)
        val editors = manager.openFile(file, true)
        // The explorer viewer is placed after the default HTML editor; select it so the rendered page
        // is shown first…
        manager.setSelectedEditor(file, AtlasFileEditorProvider.EDITOR_TYPE_ID)
        // …and, asked for a route (a node, a report), show that rather than the dashboard
        if (hash != null) editors.filterIsInstance<AtlasFileEditor>().firstOrNull()?.navigate(hash)
    }
}
