package com.flowable.atlas.explorer

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefApp

/**
 * Contributes an "Atlas Explorer" tab (rendered by JCEF) for any `*.explorer.html` file, placed
 * after the platform's default HTML editor. Only offered when JCEF is available in the running IDE;
 * otherwise users can still open the page in their external browser.
 */
class AtlasFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        !file.isDirectory &&
            file.name.endsWith(".explorer.html", ignoreCase = true) &&
            JBCefApp.isSupported()

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = AtlasFileEditor(file)

    override fun getEditorTypeId(): String = "flowable-atlas-explorer"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}
