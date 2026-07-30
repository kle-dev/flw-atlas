package com.flowable.atlas.action

import com.flowable.atlas.generate.dto.DataObjectDtoService
import com.flowable.atlas.generate.dto.DtoSource
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Tools → Flowable Atlas → Generate → Data-Object DTOs → "From App(s)…": open the "Generate
 * Data-Object DTOs" dialog on the app source — every data object an app declares, preselected, with
 * the target source root, package and per-app nesting configurable before anything is written.
 */
class GenerateDtoFromAppsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        DataObjectDtoService.getInstance(project).openDialog(DtoSource.APPS)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
