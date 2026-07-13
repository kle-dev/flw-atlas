package com.flowable.atlas.explorer

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefApp

/**
 * Opens a generated `*.explorer.html` inside the IDE and, when JCEF is available, brings its embedded
 * "Atlas Explorer" viewer tab ([AtlasFileEditor], contributed by [AtlasFileEditorProvider]) to the
 * front — so the rendered, interactive page shows immediately instead of the HTML source. Shared by
 * the post-generation balloon ([AtlasExplorerNotifier]) and the "Open Atlas Explorer" action, so a
 * page can be viewed without switching to an external browser.
 */
object AtlasExplorerOpener {

    fun openInIde(project: Project, file: VirtualFile) {
        val manager = FileEditorManager.getInstance(project)
        manager.openFile(file, true)
        // The explorer viewer is placed after the default HTML editor; select it so the rendered page
        // is shown first. Only when JCEF is available — otherwise no such tab exists and the default
        // editor stays selected (the page can still be opened in an external browser).
        if (JBCefApp.isSupported()) {
            manager.setSelectedEditor(file, AtlasFileEditorProvider.EDITOR_TYPE_ID)
        }
    }
}
