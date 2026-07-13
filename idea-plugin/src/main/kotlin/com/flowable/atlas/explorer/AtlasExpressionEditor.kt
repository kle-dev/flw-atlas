package com.flowable.atlas.explorer

import com.flowable.atlas.expr.toolwindow.FlowableExpressionPanel
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * Extra editor tab that embeds the Flowable Expressions playground ([FlowableExpressionPanel]) next to
 * the Atlas Explorer for a `*.explorer.html` (see [AtlasExpressionEditorProvider]). The whole-project
 * map and the expression playground then share one window, so there's a single unified view rather
 * than a separate tool window and a separate explorer.
 */
class AtlasExpressionEditor(project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val panel = FlowableExpressionPanel(project)

    init {
        // the platform disposes FileEditors via Disposer — take the panel (alarms, listeners) down with us
        Disposer.register(this, panel)
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel.focusComponent
    override fun getName(): String = "Flowable Expressions"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {}
}
