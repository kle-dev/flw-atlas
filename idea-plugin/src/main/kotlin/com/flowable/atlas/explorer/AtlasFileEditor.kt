package com.flowable.atlas.explorer

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * Embedded viewer for a self-contained Atlas explorer HTML, rendered with JCEF. Registered for any
 * `*.explorer.html` file as an extra editor tab after the default editor (see
 * [AtlasFileEditorProvider]), so a page generated into the project can be viewed without leaving
 * the IDE.
 */
class AtlasFileEditor(private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val browser = JBCefBrowser()

    init {
        Disposer.register(this, browser)
        browser.loadURL(file.url)
    }

    override fun getComponent(): JComponent = browser.component
    override fun getPreferredFocusedComponent(): JComponent = browser.component
    override fun getName(): String = "Atlas Explorer"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {}
}
