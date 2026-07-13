package com.flowable.atlas.navigation

import com.flowable.atlas.completion.KeySite
import com.flowable.atlas.completion.SiteMatching
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceExpression
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
        // Constant references at key sites (`startProcessInstanceByKey(ModelConstants.FOO)`) —
        // the generated model-constants pattern — navigate to the model file too. Soft reference:
        // the field reference itself stays primary, the broken-key inspection reports misses.
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PsiReferenceExpression::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                    val ref = element as? PsiReferenceExpression ?: return PsiReference.EMPTY_ARRAY
                    if (ref.parent !is PsiExpressionList) return PsiReference.EMPTY_ARRAY
                    val (site, value) = SiteMatching.keySiteForArgument(ref) ?: return PsiReference.EMPTY_ARRAY
                    return arrayOf(FlowableKeyConstantReference(ref, site, value))
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

/** Resolves a constant reference used as a key argument to the model file(s) declaring the key. */
private class FlowableKeyConstantReference(
    ref: PsiReferenceExpression,
    private val site: KeySite,
    private val key: String,
) : PsiReferenceBase.Poly<PsiReferenceExpression>(ref, TextRange(0, ref.textLength), true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val service = element.project.service<FlowableModelIndexService>()
        val psiManager = PsiManager.getInstance(element.project)
        return service.find(key)
            .filter { it.type in site.targetTypes }
            .mapNotNull { psiManager.findFile(it.file) }
            .map { PsiElementResolveResult(it) }
            .toTypedArray()
    }

    override fun getVariants(): Array<Any> = emptyArray()
}

/** The range covering the string content (excluding the surrounding quotes). */
private fun innerRange(literal: PsiLiteralExpression): TextRange {
    val len = literal.textLength
    return if (len >= 2) TextRange(1, len - 1) else TextRange(0, len)
}
