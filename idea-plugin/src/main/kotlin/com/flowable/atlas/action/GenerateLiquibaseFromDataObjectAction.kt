package com.flowable.atlas.action

import com.flowable.atlas.generate.liquibase.LiquibaseScaffoldService
import com.flowable.atlas.generate.liquibase.LiquibaseSource
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Tools → Flowable Atlas → Generate → Liquibase → "From Data Object…": open the "Generate Liquibase
 * Changelogs" dialog preselected on the data-object source — a preview of every data object's
 * changelog (extracted from the bundling app export, or synthesized from its fields) with the output
 * folder and file-name pattern configurable before anything is written.
 */
class GenerateLiquibaseFromDataObjectAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        LiquibaseScaffoldService.getInstance(project).openDialog(LiquibaseSource.DATA_OBJECTS)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
