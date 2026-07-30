package com.flowable.atlas.action

import com.flowable.atlas.generate.JavaSourceRoots
import com.flowable.atlas.generate.ModelConstantsService
import com.flowable.atlas.generate.ModelConstantsSettings
import com.flowable.atlas.settings.GenerationConfigurable
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project

/**
 * Tools → Flowable Atlas → "Generate Model Constants…" (and the Atlas Hub "Generate Constants…" link):
 * generates a Java class holding every project model key as a constant. After generation the class is
 * kept in sync automatically when models are added/removed (see ModelConstantsAutoRefresher).
 *
 * The class name comes from Settings → Flowable Atlas → Generation (default [DEFAULT_FQCN]); no modal
 * prompt is shown, which is what makes the action reliable under JetBrains Remote Dev — a modal input
 * dialog raised from a backend menu action can silently fail to surface on the thin client ("nothing
 * happens on click"). Every failure now surfaces as a balloon, and a project without a Java source
 * root falls back to a folder chooser / the project root instead of dead-ending.
 */
class GenerateModelConstantsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { generate(it) }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    companion object {
        private const val DEFAULT_FQCN = "flowable.FlowableModelKeys"
        private const val GROUP_ID = "Flowable Atlas"

        /** Generate/overwrite the model-constants class; safe from the EDT. Shared by the action and the Hub. */
        fun generate(project: Project) {
            try {
                val configured = ModelConstantsSettings.getInstance(project).state.fqcn
                val usedDefault = configured.isBlank()
                val fqcn = configured.trim().ifBlank { DEFAULT_FQCN }
                if (fqcn.endsWith(".") || fqcn.substringAfterLast('.').isBlank()) {
                    notify(
                        project, "Invalid class name '$fqcn'",
                        "Fix the class name in Settings → Flowable Atlas → Generation.",
                        NotificationType.ERROR, withSettings = true,
                    )
                    return
                }

                val root = JavaSourceRoots.preferredOrChosen(project, "Select Folder for Generated Model Constants")
                if (root == null) {
                    notify(
                        project, "Cannot generate model constants",
                        "No writable location was found to place the generated class.",
                        NotificationType.ERROR,
                    )
                    return
                }

                val file = ModelConstantsService.getInstance(project).generateAndWrite(fqcn, root)
                if (file == null) {
                    notify(
                        project, "Cannot generate model constants",
                        "Could not write the class '$fqcn'.", NotificationType.ERROR,
                    )
                    return
                }
                FileEditorManager.getInstance(project).openFile(file, true)
                if (usedDefault) {
                    notify(
                        project, "Generated $fqcn",
                        "Change the class name any time in Settings → Flowable Atlas → Generation.",
                        NotificationType.INFORMATION, withSettings = true,
                    )
                }
            } catch (pce: ProcessCanceledException) {
                throw pce
            } catch (t: Throwable) {
                thisLogger().warn("Generate Model Constants failed", t)
                notify(
                    project, "Generate Model Constants failed",
                    t.message ?: t.javaClass.simpleName, NotificationType.ERROR,
                )
            }
        }

        private fun notify(
            project: Project,
            title: String,
            message: String,
            type: NotificationType,
            withSettings: Boolean = false,
        ) {
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(title, message, type)
            if (withSettings) {
                notification.addAction(NotificationAction.createSimple("Open Generation settings") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, GenerationConfigurable::class.java)
                })
            }
            notification.notify(project)
        }
    }
}
