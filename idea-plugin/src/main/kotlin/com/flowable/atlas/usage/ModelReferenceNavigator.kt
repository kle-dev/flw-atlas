package com.flowable.atlas.usage

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.awt.RelativePoint

/** Navigates to Flowable model files: opens a single one, or shows a chooser popup for several. Call on the EDT. */
object ModelReferenceNavigator {

    fun show(project: Project, files: List<VirtualFile>, title: String, at: RelativePoint?) {
        if (project.isDisposed) return
        val valid = files.filter { it.isValid }
        when (valid.size) {
            0 -> {}
            1 -> FileEditorManager.getInstance(project).openFile(valid[0], true)
            else -> {
                val byLabel = LinkedHashMap<String, VirtualFile>()
                valid.forEach { byLabel[it.presentableUrl] = it }
                val popup = JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(byLabel.keys.toList())
                    .setTitle(title)
                    .setItemChosenCallback { label ->
                        byLabel[label]?.let { FileEditorManager.getInstance(project).openFile(it, true) }
                    }
                    .createPopup()
                if (at != null) popup.show(at) else popup.showInFocusCenter()
            }
        }
    }
}
