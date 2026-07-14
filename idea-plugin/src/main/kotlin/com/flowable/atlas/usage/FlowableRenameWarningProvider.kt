package com.flowable.atlas.usage

import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.listeners.RefactoringElementListenerProvider

/**
 * Warns when a Java method or bean/delegate class that is referenced from Flowable models is renamed:
 * models reference it by *name in expression text* (`${bean.method()}`, `delegateExpression`, …), which
 * this rename does NOT update. [getListener] runs before the rename and captures the old name(s); the
 * warning (with a "Show affected models" action) fires afterwards. Matching is name-based, so it does
 * not track method parameters/overloads — it errs toward warning.
 */
class FlowableRenameWarningProvider : RefactoringElementListenerProvider {

    override fun getListener(element: PsiElement): RefactoringElementListener? {
        val displayName = when (element) {
            is PsiMethod -> element.name
            is PsiClass -> element.name ?: return null
            else -> return null
        }
        val project = element.project
        // O(1); never build the index on the refactoring thread. If it isn't cached yet we simply
        // don't warn (no reference data to check against).
        val index = project.service<FlowableModelIndexService>().cachedOrNull() ?: return null
        val names = ModelReferenceScan.namesOf(element)
        if (names.none { it in index.referencedIdentifiers || it in index.referencedClassFqns }) return null

        return object : RefactoringElementListener {
            override fun elementRenamed(newElement: PsiElement) = warn(project, displayName, names)
            override fun elementMoved(newElement: PsiElement) {}
        }
    }

    private fun warn(project: Project, displayName: String, names: Set<String>) {
        object : Task.Backgroundable(project, "Checking Flowable model references", true) {
            override fun run(indicator: ProgressIndicator) {
                val files = ModelReferenceScan.affectedModelFiles(project, names)
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) notify(project, displayName, files)
                }
            }
        }.queue()
    }

    private fun notify(project: Project, displayName: String, files: List<VirtualFile>) {
        val count = files.size
        val where = if (count == 1) "1 model file" else "$count model files"
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                "'$displayName' is referenced by Flowable models",
                "Flowable models reference it by name in expression text ($where). Those references are " +
                    "not updated by this rename — update the models manually.",
                NotificationType.WARNING,
            )
        if (files.isNotEmpty()) {
            notification.addAction(NotificationAction.createSimple("Show affected models") {
                ModelReferenceNavigator.show(project, files, "Flowable Models Referencing '$displayName'", null)
            })
        }
        notification.notify(project)
    }

    companion object {
        private const val GROUP_ID = "Flowable Atlas"
    }
}
