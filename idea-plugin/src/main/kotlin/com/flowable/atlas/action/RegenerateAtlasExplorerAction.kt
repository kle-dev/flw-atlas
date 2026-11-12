package com.flowable.atlas.action

import com.flowable.atlas.explorer.AtlasGenerationRunner
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Tools → Flowable Atlas → Generate → "Regenerate Atlas Explorer": re-runs the generator for the
 * explorer page(s) that already exist, in place, with the artifacts and folder already chosen — no dialog.
 *
 * Registered so the name exists once. The Hub's stale line, the explorer tab's banner and its toolbar all
 * offered the same thing under two different labels; they take this action's text now, and *Find Action*
 * knows the verb too.
 */
class RegenerateAtlasExplorerAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AtlasGenerationRunner.regenerate(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
