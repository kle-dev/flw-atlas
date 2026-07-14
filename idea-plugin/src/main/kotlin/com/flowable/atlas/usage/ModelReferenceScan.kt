package com.flowable.atlas.usage

import com.flowable.atlas.index.ArchiveModelScanner
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.parsing.ModelUsageLocator
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod

/**
 * Shared scan linking a Java symbol to the Flowable model files that reference it by name — inside a
 * `${…}`/`#{…}` expression or a `class`/`delegateExpression`/`expression` attribute. The match is the
 * same loose, name-based one the index uses (see the :core `ModelRefScanner`/`ModelUsageLocator`): it
 * is NOT overload- or parameter-aware.
 *
 * Reused by Find Usages ([FlowableModelUsageSearcher]), the gutter marker
 * ([FlowableModelReferenceLineMarkerProvider]) and the rename warning ([FlowableRenameWarningProvider]).
 */
object ModelReferenceScan {

    /** The name tokens a model references a Java [element] by, or empty when the element is not applicable. */
    fun namesOf(element: PsiElement): Set<String> = when (element) {
        is PsiMethod -> setOfNotNull(element.name)
        is PsiField -> setOfNotNull(element.name)
        is PsiClass -> setOfNotNull(
            element.qualifiedName,
            element.name,
            element.name?.replaceFirstChar { it.lowercaseChar() },
        )
        else -> emptySet()
    }

    /**
     * Model files (and archive entries) whose text references any of [names]. Runs its own read action,
     * so it must be called off the EDT / off the refactoring thread. Returns empty for empty [names].
     */
    fun affectedModelFiles(project: Project, names: Set<String>): List<VirtualFile> {
        if (names.isEmpty()) return emptyList()
        return ReadAction.compute<List<VirtualFile>, RuntimeException> {
            if (project.isDisposed) return@compute emptyList()
            val found = LinkedHashSet<VirtualFile>()
            forEachModelText(project) { vf, text ->
                if (names.any { text.contains(it) } && ModelUsageLocator.findUsages(text, names).isNotEmpty()) {
                    found.add(vf)
                }
            }
            found.toList()
        }
    }

    /**
     * Invokes [consumer] with every Flowable model file / archive entry in the project's content and
     * its decoded UTF-8 text. Must be called inside a read action (each caller wraps it).
     */
    fun forEachModelText(project: Project, consumer: (VirtualFile, String) -> Unit) {
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            ProgressManager.checkCanceled()
            if (!file.isDirectory && !ModelFiles.isExcluded(file.path)) {
                when {
                    ModelFiles.typeOf(file) != null ->
                        runCatching { String(file.contentsToByteArray(), Charsets.UTF_8) }.getOrNull()
                            ?.let { consumer(file, it) }
                    ArchiveModelScanner.isArchive(file) ->
                        ArchiveModelScanner.scan(file) { _, bytes, _, entryFile ->
                            consumer(entryFile, String(bytes, Charsets.UTF_8))
                        }
                }
            }
            true
        }
    }
}
