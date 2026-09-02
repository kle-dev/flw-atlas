package com.flowable.atlas.usage

import com.flowable.atlas.index.FlowableIndex
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.codeInsight.daemon.ImplicitUsageProvider
import com.intellij.openapi.components.service
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod

/**
 * Marks Java classes / methods / fields that are referenced from Flowable models (delegate classes,
 * `${bean.method()}` expressions, listeners, …) as implicitly used, so IntelliJ does not report them
 * as "unused" just because no other Java code calls them.
 */
class FlowableImplicitUsageProvider : ImplicitUsageProvider {


    override fun isImplicitUsage(element: PsiElement): Boolean {
        val index = indexOrNull(element) ?: return false
        return when (element) {
            is PsiClass ->
                element.qualifiedName?.let { index.referencedClassFqns.contains(it) } == true ||
                    beanNameReferenced(element, index)
            is PsiMethod -> index.referencedIdentifiers.contains(element.name)
            is PsiField -> index.referencedIdentifiers.contains(element.name)
            else -> false
        }
    }

    override fun isImplicitRead(element: PsiElement): Boolean = false

    override fun isImplicitWrite(element: PsiElement): Boolean = false

    private fun indexOrNull(element: PsiElement): FlowableIndex? {
        if (element !is PsiClass && element !is PsiMethod && element !is PsiField) return null
        // The unused-declaration inspection holds the read lock while it asks; building the index here
        // ran the whole model scan under that lock and froze typing on a large repository. Read the
        // cache, ask for a background build, and answer "not implicitly used" until it lands — the
        // daemon restarts then and asks again.
        val service = element.project.service<FlowableModelIndexService>()
        return service.cachedOrRequest()
    }

    private fun beanNameReferenced(cls: PsiClass, index: FlowableIndex): Boolean {
        val simple = cls.name ?: return false
        return index.referencedIdentifiers.contains(simple) ||
            index.referencedIdentifiers.contains(simple.replaceFirstChar { it.lowercaseChar() })
    }
}
