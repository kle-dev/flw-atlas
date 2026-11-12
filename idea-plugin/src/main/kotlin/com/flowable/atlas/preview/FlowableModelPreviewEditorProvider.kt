package com.flowable.atlas.preview

import com.flowable.atlas.FlowableAtlasBundle
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.usage.FlowableDiagram
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable

/**
 * Opens a process, case, decision, form or page as text and picture side by side: the model's own text
 * on the left, its diagram, decision table or form wireframe on the right ([FlowableModelPreview]).
 * Loose files and archive entries alike, so a model in a Design export is seen without unpacking it.
 *
 * Split is the default. A layout the reader picks with the editor's toggle is remembered by the
 * platform for every model file, not per file.
 */
class FlowableModelPreviewEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        !file.isDirectory &&
            ModelFiles.typeOf(file)?.let(FlowableDiagram::canRender) == true &&
            TextEditorProvider.getInstance().accept(project, file)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val text = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        return ModelEditor(text, FlowableModelPreview(project, file, ModelFiles.typeOf(file)!!))
    }

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    /** The text editor and the picture, split by default. */
    class ModelEditor internal constructor(text: TextEditor, preview: FileEditor) :
        TextEditorWithPreview(text, preview, FlowableAtlasBundle.message("preview.editor.name"), Layout.SHOW_EDITOR_AND_PREVIEW) {

        /** Going to a line shows the text, even when the reader had switched it off. */
        override fun navigateTo(navigatable: Navigatable) {
            if (getLayout() == Layout.SHOW_PREVIEW) setLayout(Layout.SHOW_EDITOR_AND_PREVIEW)
            super.navigateTo(navigatable)
        }

        /** Shows the picture, in case the reader had switched it off. */
        fun showPreview() {
            if (getLayout() == Layout.SHOW_EDITOR) setLayout(Layout.SHOW_EDITOR_AND_PREVIEW)
        }
    }

    companion object {
        const val EDITOR_TYPE_ID = "flowable-model-preview"

        /**
         * Opens [file] in this editor with its picture showing, or returns false when this editor does
         * not take the file.
         */
        fun openWithPreview(project: Project, file: VirtualFile): Boolean {
            val manager = FileEditorManager.getInstance(project)
            val editor = manager.openFile(file, true).filterIsInstance<ModelEditor>().firstOrNull() ?: return false
            editor.showPreview()
            return true
        }
    }
}
