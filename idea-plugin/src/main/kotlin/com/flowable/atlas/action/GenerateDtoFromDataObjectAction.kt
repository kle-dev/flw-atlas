package com.flowable.atlas.action

import com.flowable.atlas.generate.dto.DataObjectDtoService
import com.flowable.atlas.generate.dto.DtoSource
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Tools → Flowable Atlas → Generate → Data-Object DTOs → "From Data Object…": open the "Generate
 * Data-Object DTOs" dialog on the data-object source — every indexed data object listed, nothing
 * preselected, so the user picks exactly the ones to generate a typed Java DTO for.
 */
class GenerateDtoFromDataObjectAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        DataObjectDtoService.getInstance(project).openDialog(DtoSource.DATA_OBJECTS)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
