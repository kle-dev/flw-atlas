package com.flowable.atlas.action

import com.flowable.atlas.generate.liquibase.LiquibaseScaffoldService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Tools → Flowable Atlas → Generate → Liquibase → "From App(s)": extract every bundled Liquibase
 * changelog from every Design-export app zip in the project (no selection — all apps) and write them
 * into `src/main/resources/liquibase`, registered in the master changelog.
 */
class GenerateLiquibaseFromAppsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        LiquibaseScaffoldService.getInstance(project).generateFromApps()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
