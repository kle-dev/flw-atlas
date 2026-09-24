package com.flowable.atlas.explorer

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jdom.Element

/**
 * Contributes the "Atlas Explorer" tab (rendered by JCEF) for any `*.explorer.html` file and hides the
 * platform's default HTML "Text" editor for it — a generated, self-contained page isn't meant to be
 * hand-edited, so the rendered explorer is the only editor. Only offered when JCEF is available in the
 * running IDE; otherwise the provider bows out (the default editor stays, and the page can still be
 * opened in an external browser).
 */
class AtlasFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        !file.isDirectory &&
            // a report inside a .zip has no path to regenerate into or open in a browser (`toNioPath()` throws)
            file.isInLocalFileSystem &&
            file.name.endsWith(".explorer.html", ignoreCase = true) &&
            JcefSupport.isAvailable()

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = AtlasFileEditor(project, file)

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    override fun readState(sourceElement: Element, project: Project, file: VirtualFile): FileEditorState =
        AtlasExplorerState(sourceElement.getAttributeValue(ROUTE).orEmpty())

    override fun writeState(state: FileEditorState, project: Project, targetElement: Element) {
        val route = (state as? AtlasExplorerState)?.route.orEmpty()
        if (route.isNotEmpty()) targetElement.setAttribute(ROUTE, route)
    }

    companion object {
        /** Provider id of the embedded JCEF explorer tab; used to select it after opening (see [AtlasExplorerOpener]). */
        const val EDITOR_TYPE_ID = "flowable-atlas-explorer"
        private const val ROUTE = "route"
    }
}
