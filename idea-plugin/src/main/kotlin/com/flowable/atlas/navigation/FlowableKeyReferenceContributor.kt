package com.flowable.atlas.navigation

import com.flowable.atlas.completion.KeySite
import com.flowable.atlas.completion.SiteMatching
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.util.ProcessingContext

/**
 * Turns a Flowable model-key literal at a public-API call site into a navigable reference: Ctrl-click
 * (or Go To Declaration) jumps to the model file(s) declaring that key, and Find Usages works too.
 */
class FlowableKeyReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PsiLiteralExpression::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                    val literal = element as? PsiLiteralExpression ?: return PsiReference.EMPTY_ARRAY
                    val site = SiteMatching.keySiteForLiteral(literal) ?: return PsiReference.EMPTY_ARRAY
                    return arrayOf(FlowableKeyReference(literal, site))
                }
            },
        )
    }
}

/** Resolves a key literal to the model file(s) that declare it. */
private class FlowableKeyReference(
    literal: PsiLiteralExpression,
    private val site: KeySite,
) : PsiReferenceBase.Poly<PsiLiteralExpression>(literal, innerRange(literal), false) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val key = element.value as? String ?: return ResolveResult.EMPTY_ARRAY
        val service = element.project.service<FlowableModelIndexService>()
        val psiManager = PsiManager.getInstance(element.project)
        val results = service.find(key)
            .filter { it.type in site.targetTypes }
            .mapNotNull { psiManager.findFile(it.file) }
            .map { PsiElementResolveResult(it) }
        return results.toTypedArray()
    }

    // Completion is handled by the dedicated contributor; don't duplicate variants here.
    override fun getVariants(): Array<Any> = emptyArray()
}

/** The range covering the string content (excluding the surrounding quotes). */
private fun innerRange(literal: PsiLiteralExpression): TextRange {
    val len = literal.textLength
    return if (len >= 2) TextRange(1, len - 1) else TextRange(0, len)
}
