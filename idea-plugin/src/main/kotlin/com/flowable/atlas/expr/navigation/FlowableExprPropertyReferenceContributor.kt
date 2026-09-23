package com.flowable.atlas.expr.navigation

import com.flowable.atlas.expr.lang.FlowableExprFile
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelPaths
import com.flowable.atlas.parsing.Constants
import com.flowable.atlas.parsing.SpringProperties
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.ProcessingContext

/**
 * `${environment.getProperty('crm.base-url', '')}` — Ctrl+click on the key opens the line of every
 * `application*.properties` / `application*.yml` of the project that sets it, one per profile. Matched in
 * Spring's relaxed form (`crm.base-url` = `crm.baseUrl`), as the graph matches it. The reference is soft:
 * a property set nowhere in the repository is normal — it comes from the environment.
 */
class FlowableExprPropertyReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(FlowableExprFile::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                    val file = element as? FlowableExprFile ?: return PsiReference.EMPTY_ARRAY
                    return Constants.PROPERTY_READ_RE.findAll(file.text).map { m ->
                        val key = m.groups[1]!!
                        PropertyReference(file, TextRange(key.range.first, key.range.last + 1), key.value)
                    }.toList<PsiReference>().toTypedArray()
                }
            },
        )
    }

    private class PropertyReference(file: FlowableExprFile, range: TextRange, private val key: String) :
        PsiReferenceBase.Poly<FlowableExprFile>(file, range, true) {

        override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
            val project = element.project
            val psiManager = PsiManager.getInstance(project)
            val scope = GlobalSearchScope.projectScope(project)
            val wanted = relaxed(key)
            val targets = ArrayList<PsiElement>()
            for (ext in listOf("properties", "yml", "yaml")) {
                for (vf in FilenameIndex.getAllFilesByExt(project, ext, scope)) {
                    if (!SpringProperties.isConfigFile(vf.name)) continue
                    if (ModelFiles.projectRelative(project, vf.path)?.let(ModelPaths::isTestSource) == true) continue
                    val psi = psiManager.findFile(vf) ?: continue
                    val text = psi.text
                    for ((k, line) in SpringProperties.keys(text, vf.name)) {
                        if (relaxed(k) != wanted) continue
                        val start = StringUtil.lineColToOffset(text, line - 1, 0).takeIf { it >= 0 } ?: continue
                        val at = start + text.substring(start).takeWhile { it == ' ' || it == '\t' }.length
                        targets.add(psi.findElementAt(at) ?: psi)
                    }
                }
            }
            return PsiElementResolveResult.createResults(targets)
        }

        override fun getVariants(): Array<Any> = emptyArray()

        private fun relaxed(k: String) = k.lowercase().replace("-", "").replace("_", "")
    }
}
