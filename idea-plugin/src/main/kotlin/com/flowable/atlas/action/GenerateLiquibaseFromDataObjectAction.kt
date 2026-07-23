package com.flowable.atlas.action

import com.flowable.atlas.generate.liquibase.LiquibaseScaffoldService
import com.flowable.atlas.model.ModelType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory

/**
 * Tools → Flowable Atlas → Generate → Liquibase → "From Data Object…": pick an indexed data-object
 * key from a searchable chooser (never typed), then generate its changelog — extracted from the app
 * export that bundles it, or synthesized from the data object's fields when none is bundled.
 *
 * The data-object keys are gathered off the EDT (the index may build) before the chooser is shown, so
 * the action never blocks the UI thread.
 */
class GenerateLiquibaseFromDataObjectAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        object : Task.Backgroundable(project, "Loading data objects", true) {
            override fun run(indicator: ProgressIndicator) {
                val keys = LiquibaseScaffoldService.getInstance(project).keysOfType(ModelType.DATA_OBJECT)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    if (keys.isEmpty()) {
                        notifyNoDataObjects(project)
                    } else {
                        showChooser(project, keys)
                    }
                }
            }
        }.queue()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun showChooser(project: Project, keys: List<String>) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(keys)
            .setTitle("Generate Liquibase Changelog from Data Object")
            .setItemChosenCallback { key ->
                LiquibaseScaffoldService.getInstance(project).generateFromDataObject(key)
            }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun notifyNoDataObjects(project: Project) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Flowable Atlas")
            .createNotification(
                "No data objects found",
                "The project index holds no data-object models to generate a changelog from.",
                NotificationType.WARNING,
            )
            .notify(project)
    }
}
