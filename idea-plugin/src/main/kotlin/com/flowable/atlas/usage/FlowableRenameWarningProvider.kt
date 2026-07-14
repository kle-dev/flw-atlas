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
        val names = ModelReferenceScan.namesOf(element)
        if (names.isEmpty()) return null
        val project = element.project
        // Fast path: when the index is already built, only attach a listener if the symbol is actually
        // referenced — no background work / task flash for unrelated renames. When the index is NOT yet
        // cached (e.g. right after opening the project) we must not build it on the refactoring thread,
        // so attach a listener anyway and decide in the background (warn() re-checks and no-ops if not
        // referenced). This is what makes the warning reliable regardless of index-build timing.
        val cached = project.service<FlowableModelIndexService>().cachedOrNull()
        if (cached != null && names.none { it in cached.referencedIdentifiers || it in cached.referencedClassFqns }) {
            return null
        }

        return object : RefactoringElementListener {
            override fun elementRenamed(newElement: PsiElement) = warn(project, displayName, names)
            override fun elementMoved(newElement: PsiElement) {}
        }
    }

    private fun warn(project: Project, displayName: String, names: Set<String>) {
        object : Task.Backgroundable(project, "Checking Flowable model references", true) {
            override fun run(indicator: ProgressIndicator) {
                if (project.isDisposed) return
                val service = project.service<FlowableModelIndexService>()
                val index = service.cachedOrNull() ?: service.index()   // build once, off the EDT, if needed
                if (names.none { it in index.referencedIdentifiers || it in index.referencedClassFqns }) return
                val files = ModelReferenceScan.affectedModelFiles(project, names)
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) notify(project, displayName, files)
                }
            }
        }.queue()
    }

    private fun notify(project: Project, displayName: String, files: List<VirtualFile>) {
        val where = when (files.size) {
            0 -> "Flowable models"
            1 -> "1 Flowable model"
            else -> "${files.size} Flowable models"
        }
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                "Rename not applied to Flowable models",
                "'$displayName' is used by $where as expression text (e.g. \${bean.$displayName()}). " +
                    "Renaming the Java symbol does not update the models — open them and adjust the " +
                    "reference by hand.",
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
