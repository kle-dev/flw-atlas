package com.flowable.keys.action

import com.flowable.keys.index.FlowableIndex
import com.flowable.keys.index.FlowableModelIndexService
import com.flowable.keys.model.ModelType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages

/**
 * Debug helper (Tools → "Flowable: Dump Key Index"): rescans the project and shows a
 * per-type summary of the indexed model keys. Used to verify M0 before the completion
 * features are wired up.
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

        Messages.showMessageDialog(project, render(index), "Flowable Key Index", Messages.getInformationIcon())
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.PROJECT) != null
    }

    private fun render(index: FlowableIndex): String {
        val byType = index.allDistinct().groupBy { it.type }
        val sb = StringBuilder()
        sb.append("Indexed ").append(index.distinctCount()).append(" Flowable models.\n\n")
        for (type in ModelType.entries) {
            val list = byType[type] ?: continue
            sb.append(type.display).append(": ").append(list.size).append('\n')
            list.take(5).forEach { sb.append("    ").append(it.key).append("  —  ").append(it.name).append('\n') }
            if (list.size > 5) sb.append("    …\n")
        }
        return sb.toString()
    }
}
