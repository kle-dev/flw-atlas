package com.flowable.atlas.explorer

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.util.function.Function
import javax.swing.JComponent

/**
 * A banner above an open Atlas explorer whose models have changed since it was generated, with the
 * one action that fixes it. The tab used to render a snapshot that could be weeks old and say nothing;
 * the Hub's hint was the only sign, and only after a Design pull.
 */
class AtlasExplorerStaleNotificationProvider : EditorNotificationProvider, DumbAware {

    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (!file.name.endsWith(".explorer.html")) return null
        val changedAt = AtlasExplorerStaleness.latestModelChange(project) ?: return null
        if (!AtlasExplorerStaleness.isStale(listOf(file.timeStamp), changedAt)) return null
        return Function { editor ->
            val atlasEditor = editor as? AtlasFileEditor ?: return@Function null
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
                text = "Models changed since this explorer was generated."
                createActionLabel("Regenerate") { atlasEditor.regenerate() }
            }
        }
    }
}
