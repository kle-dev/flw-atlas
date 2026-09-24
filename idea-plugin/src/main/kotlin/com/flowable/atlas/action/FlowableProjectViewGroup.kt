package com.flowable.atlas.action

import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelPaths
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware

/**
 * The Project-view context menu entries — *Open in Atlas Explorer* on a model, *Compare Model with
 * Archive*, *Generate Atlas Explorer…* — shown on a folder, a model file, an archive or a `.json`, and on
 * nothing else. The Hub stays the plugin's surface; this is the one place a right-click on the models
 * themselves should not come up empty. *Go to Model…* used to be here too; it opens a search and has
 * nothing to do with what was right-clicked, so it made way for the model's own page.
 *
 * The `.json` is there for the comparison: a model generated into the project folder is a `.json` that
 * [ModelFiles.typeOf] only recognises inside a Design `*-models/` folder, and only while *Index Flowable
 * Design workspace* is on — so the file this menu is most wanted on was the one file it was hidden from.
 * Widening the gate costs an entry on the odd `package.json`; the actions themselves stay disabled there.
 */
class FlowableProjectViewGroup : DefaultActionGroup(), DumbAware {

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null &&
            (
                file.isDirectory || ModelFiles.typeOf(file) != null || ModelPaths.isArchive(file.name) ||
                    file.name.endsWith(".json", ignoreCase = true)
                )
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
