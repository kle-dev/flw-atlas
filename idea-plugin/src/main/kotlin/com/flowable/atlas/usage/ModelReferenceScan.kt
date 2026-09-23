package com.flowable.atlas.usage

import com.flowable.atlas.index.ArchiveModelScanner
import com.flowable.atlas.index.ProjectModelScope
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.parsing.ModelUsageLocator
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
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
     * Model files (and archive entries) whose text references any of [names]. Takes the read lock only to
     * list the files, and must be called off the EDT / off the refactoring thread. Returns empty for empty [names].
     */
    fun affectedModelFiles(project: Project, names: Set<String>): List<VirtualFile> =
        affectedModelUsages(project, names).keys.toList()

    /**
     * The same files, each with the offset of its **first** usage — what a gutter click opens at, so a
     * deployment XML holding three processes lands on the `${bean…}` and not on line 1. Same threading rule.
     */
    fun affectedModelUsages(project: Project, names: Set<String>): Map<VirtualFile, Int> {
        if (names.isEmpty()) return emptyMap()
        val found = LinkedHashMap<VirtualFile, Int>()
        forEachModelTextUnlocked(project) { vf, text ->
            if (names.none { text.contains(it) }) return@forEachModelTextUnlocked
            val first = ModelUsageLocator.findUsages(text, names).firstOrNull() ?: return@forEachModelTextUnlocked
            found.putIfAbsent(vf, first.first)
        }
        return found
    }

    /**
     * Invokes [consumer] with every Flowable model file / archive entry in the project's content and
     * its decoded UTF-8 text. Must be called inside a read action — for callers that go on to PSI
     * (Find Usages); everything else takes [forEachModelTextUnlocked].
     */
    fun forEachModelText(project: Project, consumer: (VirtualFile, String) -> Unit) {
        for (file in modelFiles(project)) scan(file, consumer)
    }

    /**
     * [forEachModelText] without holding the read lock while reading: the files are listed under a short
     * read action, then read and scanned outside it. One read action around every model and archive in
     * the project — for a gutter click or the rename warning — blocked any write, so typing froze until
     * the scan was done. Must be called off the EDT.
     */
    fun forEachModelTextUnlocked(project: Project, consumer: (VirtualFile, String) -> Unit) {
        val files = ReadAction.computeBlocking<List<VirtualFile>, RuntimeException> {
            if (project.isDisposed) emptyList() else modelFiles(project)
        }
        for (file in files) {
            ProgressManager.checkCanceled()
            if (project.isDisposed) return
            if (file.isValid) scan(file, consumer)
        }
    }

    /** The model files and archives in scope. Touches no content; must be called inside a read action. */
    private fun modelFiles(project: Project): List<VirtualFile> {
        // The same scope as the model index (the active sub-project, else the content roots) — a
        // Find Usages that answered from every app while the index was narrowed to one was two scopes
        // for one query.
        val excluded = ModelFiles.excluder(project)
        val out = ArrayList<VirtualFile>()
        ProjectModelScope.iterateFiles(project) { file ->
            if (!file.isDirectory && !excluded(file.path) &&
                (ModelFiles.typeOf(file) != null || ArchiveModelScanner.isArchive(file))
            ) out.add(file)
            true
        }
        return out
    }

    private fun scan(file: VirtualFile, consumer: (VirtualFile, String) -> Unit) {
        when {
            ModelFiles.typeOf(file) != null ->
                runCatching { String(file.contentsToByteArray(), Charsets.UTF_8) }.getOrNull()
                    ?.let { consumer(file, it) }
            ArchiveModelScanner.isArchive(file) ->
                // A caller may hold the read lock: a synchronous jar-FS refresh under it is the deadlock
                // the scanner's own doc warns about.
                ArchiveModelScanner.scan(file, allowRefresh = false) { _, bytes, _, entryFile ->
                    consumer(entryFile, String(bytes, Charsets.UTF_8))
                }
        }
    }
}
