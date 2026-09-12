package com.flowable.atlas.navigation

import com.flowable.atlas.completion.FlowableJsonKeyCatalog
import com.flowable.atlas.completion.FlowableXmlKeyCatalog
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.parsing.JsonKeySites.JsonKeySite
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.components.service
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.util.ProcessingContext

/**
 * Ctrl+click on a model key inside a Flowable **JSON** model — a data object's
 * `referencedServiceDefinitionModelKey`, a form component's `formRef` or `dataObjectDefinitionKey`, a
 * document's `forms`, an app's `childModels` — jumps to the referenced model at its key, and Find Usages
 * on a model's key lists these sites. The XML models had this since the first release; the JSON ones,
 * which carry most of a project's references, did not. What counts as a reference is
 * [com.flowable.atlas.parsing.JsonKeySites], shared with the CLI's parsers.
 */
class FlowableJsonKeyReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(JsonStringLiteral::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                    val literal = element as? JsonStringLiteral ?: return PsiReference.EMPTY_ARRAY
                    val value = literal.value
                    // a `{{binding}}` or `${expression}` names a key at run time — nothing to jump to
                    if (!FlowableXmlKeyCatalog.isResolvableKey(value) || value.contains("{{")) return PsiReference.EMPTY_ARRAY
                    val site = FlowableJsonKeyCatalog.siteForLiteral(literal) ?: return PsiReference.EMPTY_ARRAY
                    return arrayOf(FlowableJsonKeyReference(literal, site))
                }
            },
        )
    }
}

/**
 * Resolves a JSON key to the model file(s) declaring it. Soft: an unknown key is the report's
 * `missingRefs` finding, not a red "cannot resolve" in the editor — the reference exists to navigate.
 */
private class FlowableJsonKeyReference(
    literal: JsonStringLiteral,
    private val site: JsonKeySite,
) : PsiReferenceBase.Poly<JsonStringLiteral>(literal, ElementManipulators.getValueTextRange(literal), true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val key = element.value
        // A reference resolves under the read lock — the cached index only, never a build here.
        val index = element.project.service<FlowableModelIndexService>().cachedOrRequest() ?: return ResolveResult.EMPTY_ARRAY
        return ModelKeyTargets.resolve(element.project, index.find(key).filter { it.type in site.types })
    }

    // Completion inside model JSON is not offered (yet); no variants here either.
    override fun getVariants(): Array<Any> = emptyArray()
}
