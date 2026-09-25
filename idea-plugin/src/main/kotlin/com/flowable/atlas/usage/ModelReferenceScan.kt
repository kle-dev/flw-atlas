package com.flowable.atlas.usage

import com.flowable.atlas.index.ArchiveModelScanner
import com.flowable.atlas.index.FlowableIndex
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
 * Shared scan linking a Java symbol to the Flowable model files that use it — a method or field through one
 * of its class's beans (`${orderService.place(…)}`), a class as an expression's bean or by its FQN in a
 * `class` attribute (see [JavaRef] and the :core `ModelUsageLocator.findJavaUsages`). It is not overload-
 * or parameter-aware: `place` is every `place` of the bean's class.
 *
 * Reused by Find Usages ([FlowableModelUsageSearcher]), the gutter marker
 * ([FlowableModelReferenceLineMarkerProvider]) and the rename warning ([FlowableRenameWarningProvider]).
 */
object ModelReferenceScan {

    /**
     * What a model has to say to use a Java symbol: a bean of its class ([beans], see [SpringBeans]) with,
     * for a method or a field, the member itself ([members] — a getter also by the property it reads);
     * for a class, the bean as an expression root, or its FQN in a `class` attribute. Matching the bare
     * name instead marked every `getId` wherever any expression called any `getId`.
     */
    data class JavaRef(val beans: Set<String>, val members: Set<String>, val fqn: String?) {
        /** Whether the index says some model uses the symbol — the cheap check before any scan. */
        fun usedIn(index: FlowableIndex): Boolean =
            if (members.isNotEmpty()) beans.any { b -> members.any { "$b#$it" in index.beanMembers } }
            else (fqn != null && fqn in index.referencedClassFqns) || beans.any { it in index.expressionRoots }

        /** The offset ranges in a model's [text] where the symbol is used. */
        fun findIn(text: String): List<IntRange> = ModelUsageLocator.findJavaUsages(text, beans, members, fqn)

        /** Cheap text pre-check: none of the names occurs at all. */
        fun absentFrom(text: String): Boolean = beans.none { text.contains(it) } && (fqn == null || !text.contains(fqn))
    }

    /** The [JavaRef] of a Java [element], or null when models cannot reference it (or it has no bean). */
    fun refOf(element: PsiElement): JavaRef? = when (element) {
        is PsiMethod -> element.containingClass?.let { c ->
            JavaRef(SpringBeans.namesOf(c), setOfNotNull(element.name, propertyOf(element)), null)
        }
        is PsiField -> element.containingClass?.let { c -> JavaRef(SpringBeans.namesOf(c), setOfNotNull(element.name), null) }
        is PsiClass -> JavaRef(SpringBeans.namesOf(element), emptySet(), element.qualifiedName)
        else -> null
    }?.takeIf { it.beans.isNotEmpty() || it.fqn != null }

    /** `getTotal()` / `isActive()` with no parameters is read by `${bean.total}` / `${bean.active}`. */
    private fun propertyOf(m: PsiMethod): String? {
        if (m.parameterList.parametersCount != 0) return null
        val n = m.name
        val base = when {
            n.length > 3 && n.startsWith("get") && n[3].isUpperCase() -> n.substring(3)
            n.length > 2 && n.startsWith("is") && n[2].isUpperCase() -> n.substring(2)
            else -> return null
        }
        return base.replaceFirstChar { it.lowercaseChar() }
    }

    /**
     * Model files (and archive entries) that use [ref]. Takes the read lock only to list the files, and must
     * be called off the EDT / off the refactoring thread.
     */
    fun affectedModelFiles(project: Project, ref: JavaRef): List<VirtualFile> =
        affectedModelUsages(project, ref).keys.toList()

    /**
     * The same files, each with the offset of its **first** usage — what a gutter click opens at, so a
     * deployment XML holding three processes lands on the `${bean…}` and not on line 1. Same threading rule.
     */
    fun affectedModelUsages(project: Project, ref: JavaRef): Map<VirtualFile, Int> {
        val found = LinkedHashMap<VirtualFile, Int>()
        forEachModelTextUnlocked(project) { vf, text ->
            if (ref.absentFrom(text)) return@forEachModelTextUnlocked
            val first = ref.findIn(text).firstOrNull() ?: return@forEachModelTextUnlocked
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
