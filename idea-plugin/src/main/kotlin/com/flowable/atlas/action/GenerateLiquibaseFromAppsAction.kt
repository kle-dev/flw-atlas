package com.flowable.atlas.action

import com.flowable.atlas.generate.liquibase.LiquibaseScaffoldService
import com.flowable.atlas.generate.liquibase.LiquibaseSource
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Tools → Flowable Atlas → Generate → Liquibase → "From App(s)…": open the "Generate Liquibase
 * Changelogs" dialog preselected on the app-export source — a preview of every bundled Liquibase
 * changelog found in the project's Design-export zips, with the output folder and file-name pattern
 * configurable before anything is written.
 */
class GenerateLiquibaseFromAppsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        LiquibaseScaffoldService.getInstance(project).openDialog(LiquibaseSource.APPS)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
