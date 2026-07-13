package com.flowable.atlas.action

import com.flowable.atlas.index.FlowableIndex
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages

/**
 * Internal debug helper (Tools → Flowable Atlas → "Dump Key Index (Internal)", visible only with
 * `idea.is.internal=true`): rescans the project synchronously and shows a per-type summary of the
 * indexed model keys. End users get the same information from "Rebuild Model Index".
 */
class DumpFlowableIndexAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.service<FlowableModelIndexService>()

        val index = ProgressManager.getInstance().runProcessWithProgressSynchronously<FlowableIndex, RuntimeException>(
            { service.refresh() },
            "Indexing Flowable Models",
            true,
            project,
        )

        Messages.showMessageDialog(project, RebuildModelIndexAction.render(index), "Model Index", Messages.getInformationIcon())
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.PROJECT) != null
    }
}
