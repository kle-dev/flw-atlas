package com.flowable.atlas.navigation

import com.flowable.atlas.index.ModelEntry
import com.flowable.atlas.model.ModelKeyDeclaration
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.ResolveResult

/**
 * Where a jump to a model lands: on the key's declaration, not on line 1 of its file.
 *
 * A model key used to resolve to the `PsiFile`, which for a minified Design JSON or a deployment XML
 * holding three processes means the top of the file with nothing saying where the key is. The
 * declaration is one attribute (`<process id="…">`) or one property (`"key": "…"`); [ModelKeyDeclaration]
 * finds it in the text, and the leaf element there is what Ctrl+click, Go to Declaration and the
 * Search Everywhere row navigate to. A file whose text does not declare the key that way still resolves
 * to the file, as before.
 */
internal object ModelKeyTargets {

    /** The PSI element a jump to [entry] lands on — the key's declaration when the text has one, else the file. */
    fun target(psiManager: PsiManager, entry: ModelEntry): PsiElement? {
        val psiFile = psiManager.findFile(entry.file) ?: return null
        val offset = ModelKeyDeclaration.offsetOf(psiFile.text, entry.type, entry.key) ?: return psiFile
        return psiFile.findElementAt(offset) ?: psiFile
    }

    fun resolve(project: Project, entries: List<ModelEntry>): Array<ResolveResult> {
        val psiManager = PsiManager.getInstance(project)
        return entries.mapNotNull { target(psiManager, it) }.map { PsiElementResolveResult(it) }.toTypedArray()
    }

    /** `(line, column)` of [entry]'s key declaration in its file, both 0-based — null when the text does not declare it. */
    fun lineColumn(entry: ModelEntry): Pair<Int, Int>? {
        val text = runCatching { String(entry.file.contentsToByteArray(), Charsets.UTF_8) }.getOrNull() ?: return null
        val offset = ModelKeyDeclaration.offsetOf(text, entry.type, entry.key) ?: return null
        return ModelKeyDeclaration.lineColumn(text, offset)
    }
}
