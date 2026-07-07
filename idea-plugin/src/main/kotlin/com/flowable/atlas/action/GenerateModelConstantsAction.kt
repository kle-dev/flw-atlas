package com.flowable.atlas.action

import com.flowable.atlas.generate.ModelConstantsService
import com.flowable.atlas.generate.ModelConstantsSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile

/**
 * Tools → "Flowable: Generate Model Constants": generates a Java class holding every project
 * model key as a constant. After generation, the class is kept in sync automatically when models
 * are added/removed (see ModelConstantsAutoRefresher).
 */
class GenerateModelConstantsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = ModelConstantsSettings.getInstance(project).state
        val default = settings.fqcn.ifBlank { "flowable.FlowableModelKeys" }

        val fqcn = Messages.showInputDialog(
            project,
            "Fully-qualified class name for the generated model-constants class:",
            "Generate Flowable Model Constants",
            null,
            default,
            null,
        )?.trim()
        if (fqcn.isNullOrBlank()) return
        if (fqcn.endsWith(".") || fqcn.substringAfterLast('.').isBlank()) {
            Messages.showErrorDialog(project, "Invalid class name: '$fqcn'.", "Flowable Atlas")
            return
        }

        val root = pickSourceRoot(project)
        if (root == null) {
            Messages.showErrorDialog(project, "No source root found to place the generated class.", "Flowable Atlas")
            return
        }

        val file = ModelConstantsService.getInstance(project).generateAndWrite(fqcn, root)
        if (file != null) {
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    /** Prefer a main Java source root; fall back to any non-test, non-resources source root. */
    private fun pickSourceRoot(project: Project): VirtualFile? {
        val roots = ProjectRootManager.getInstance(project).contentSourceRoots.toList()
        return roots.firstOrNull { it.path.contains("/src/main/java") }
            ?: roots.firstOrNull { !it.path.contains("/test") && !it.path.contains("/resources") }
            ?: roots.firstOrNull()
    }
}
