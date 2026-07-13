package com.flowable.atlas.explorer

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Adds a "Flowable Expressions" tab (the playground) to any `*.explorer.html`, placed after the Atlas
 * Explorer tab. Together with [AtlasFileEditorProvider] hiding the default text editor, opening an
 * explorer shows just two tabs — Atlas Explorer and Flowable Expressions — one unified in-IDE view.
 * Independent of JCEF (the playground is plain Swing), so it appears even where the explorer viewer
 * cannot.
 */
class AtlasExpressionEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        !file.isDirectory && file.name.endsWith(".explorer.html", ignoreCase = true)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = AtlasExpressionEditor(project, file)

    override fun getEditorTypeId(): String = "flowable-atlas-expressions"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}
