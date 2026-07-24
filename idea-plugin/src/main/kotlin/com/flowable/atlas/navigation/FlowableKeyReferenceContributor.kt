package com.flowable.atlas.navigation

import com.flowable.atlas.completion.FluentChain
import com.flowable.atlas.completion.KeySite
import com.flowable.atlas.completion.OperationSite
import com.flowable.atlas.completion.SiteMatching
import com.flowable.atlas.completion.ValueKeyMatching
import com.flowable.atlas.completion.ValueSite
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethodCallExpression
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
 *
 * Operation-name (`.operation("…")`) and value-field (`.value("…", …)`) literals navigate too — to
 * the backing service model that declares the operation catalog (a data object via its backing
 * service), resolved exactly as completion resolves the offered operations.
 */
class FlowableKeyReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PsiLiteralExpression::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                    val literal = element as? PsiLiteralExpression ?: return PsiReference.EMPTY_ARRAY
                    // A model-KEY literal → the file(s) declaring that key.
                    SiteMatching.keySiteForLiteral(literal)?.let { return arrayOf(FlowableKeyReference(literal, it)) }
                    // An operation-name / value-field literal → the backing service model file.
                    operationModelReference(literal)?.let { return arrayOf(it) }
                    // Value-based (opt-in): any literal whose value equals a known model key navigates too,
                    // even with no call site. cachedOrNull() only — a reference provider must not build.
                    valueKeyReference(literal)?.let { return arrayOf(it) }
                    return PsiReference.EMPTY_ARRAY
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
        return resolveKeyToModelFiles(element.project, key, site.targetTypes)
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

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        resolveKeyToModelFiles(element.project, key, site.targetTypes)

    override fun getVariants(): Array<Any> = emptyArray()
}

/**
 * Resolves an operation-name / value-field literal to the backing service model file whose operation
 * catalog it scopes to: the service named by the sibling `serviceKey(…)`, or a data object's backing
 * service (via its `referencedServiceDefinitionModelKey`) — the same resolution the completion
 * contributor uses, so navigation and completion agree.
 */
private class FlowableOperationModelReference(
    literal: PsiLiteralExpression,
    private val call: PsiMethodCallExpression,
    private val keyMethod: String,
    private val keyIsService: Boolean,
) : PsiReferenceBase.Poly<PsiLiteralExpression>(literal, innerRange(literal), false) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val project = element.project
        val chain = FluentChain.collectCalls(call)
        val keyCall = FluentChain.findCall(chain, keyMethod) ?: return ResolveResult.EMPTY_ARRAY
        val siblingKey = FluentChain.constantStringArg(keyCall, 0, project) ?: return ResolveResult.EMPTY_ARRAY
        val serviceKey = if (keyIsService) siblingKey
            else project.service<FlowableModelIndexService>().backingServiceKey(siblingKey) ?: return ResolveResult.EMPTY_ARRAY
        return resolveKeyToModelFiles(project, serviceKey, listOf(ModelType.SERVICE))
    }

    override fun getVariants(): Array<Any> = emptyArray()
}

/**
 * If [literal] is a String argument at an [OperationSite] or [ValueSite], a reference navigating to
 * the backing service model file; null otherwise. Both site kinds carry the sibling key method and
 * whether that key names a service directly.
 */
private fun operationModelReference(literal: PsiLiteralExpression): PsiReference? {
    if (literal.value !is String) return null
    val argList = literal.parent as? PsiExpressionList ?: return null
    val call = argList.parent as? PsiMethodCallExpression ?: return null
    val argIndex = argList.expressions.indexOf(literal)
    val (keyMethod, keyIsService) = when (val site = SiteMatching.siteAt(call, argIndex)) {
        is OperationSite -> site.keyMethod to site.keyIsService
        is ValueSite -> site.keyMethod to site.keyIsService
        else -> return null
    }
    return FlowableOperationModelReference(literal, call, keyMethod, keyIsService)
}

/**
 * Resolves a value-matched key literal (recognized anywhere, not at a call site — opt-in via
 * [ValueKeyMatching]) to its model file(s). Since there is no call site to narrow the type, it resolves
 * across all model types. Uses the cached index only (never builds from a reference pass); returns null
 * — so no reference — until the index exists.
 */
private class FlowableValueKeyReference(
    literal: PsiLiteralExpression,
) : PsiReferenceBase.Poly<PsiLiteralExpression>(literal, innerRange(literal), false) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val key = element.value as? String ?: return ResolveResult.EMPTY_ARRAY
        return resolveKeyToModelFiles(element.project, key, ModelType.entries)
    }

    override fun getVariants(): Array<Any> = emptyArray()
}

/** A value-based key reference for [literal], or null if disabled, implausible, or not a known key. */
private fun valueKeyReference(literal: PsiLiteralExpression): PsiReference? {
    if (!ValueKeyMatching.enabled()) return null
    val value = literal.value as? String ?: return null
    if (!ValueKeyMatching.plausible(value)) return null
    val index = literal.project.service<FlowableModelIndexService>().cachedOrNull() ?: return null
    if (index.find(value).isEmpty()) return null
    return FlowableValueKeyReference(literal)
}

/** The shared model-key → PsiFile mapping: the file(s) declaring [key] as one of [types]. */
private fun resolveKeyToModelFiles(project: Project, key: String, types: Collection<ModelType>): Array<ResolveResult> {
    val service = project.service<FlowableModelIndexService>()
    val psiManager = PsiManager.getInstance(project)
    return service.find(key)
        .filter { it.type in types }
        .mapNotNull { psiManager.findFile(it.file) }
        .map { PsiElementResolveResult(it) }
        .toTypedArray()
}

/** The range covering the string content (excluding the surrounding quotes). */
private fun innerRange(literal: PsiLiteralExpression): TextRange {
    val len = literal.textLength
    return if (len >= 2) TextRange(1, len - 1) else TextRange(0, len)
}
