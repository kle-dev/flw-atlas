package com.flowable.atlas.action

import com.flowable.atlas.index.FlowableIndex
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * Tools → Flowable Atlas → "Rebuild Model Index": rescans the project's Flowable models in the
 * background (non-modal, unlike the internal dump action) and reports the result as a balloon with
 * a per-type breakdown behind "Show details". The Atlas Hub's Rebuild button runs the same path.
 */
class RebuildModelIndexAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        rebuild(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    companion object {

        /** Rebuilds the index under a background task; safe to call from the EDT. */
        fun rebuild(project: Project) {
            object : Task.Backgroundable(project, "Indexing Flowable models", true) {
                override fun run(indicator: ProgressIndicator) {
                    val index = project.service<FlowableModelIndexService>().refresh()
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) notifyRebuilt(project, index)
                    }
                }
            }.queue()
        }

        private fun notifyRebuilt(project: Project, index: FlowableIndex) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Flowable Atlas")
                .createNotification(
                    "Model index rebuilt",
                    "${index.distinctCount()} models indexed",
                    NotificationType.INFORMATION,
                )
                .addAction(NotificationAction.createSimple("Show details") {
                    Messages.showMessageDialog(project, render(index), "Model Index", Messages.getInformationIcon())
                })
                .notify(project)
        }

        /** Per-type breakdown, shared with the internal dump action's dialog. */
        fun render(index: FlowableIndex): String {
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
}
