package com.flowable.keys.navigation

import com.flowable.keys.completion.FlowableXmlKeyCatalog
import com.flowable.keys.completion.FlowableXmlKeyCatalog.XmlKeySite
import com.flowable.keys.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.patterns.XmlPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.util.ProcessingContext

/**
 * Turns a Flowable model-key reference inside model XML (BPMN `calledElement`, `flowable:formKey`,
 * CMMN `caseRef`/`processRef`/`decisionRef`, …) into a navigable reference: Ctrl-click jumps to the
 * referenced model file, and Find Usages works. See [FlowableXmlKeyCatalog].
 */
class FlowableXmlKeyReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            XmlPatterns.xmlAttributeValue(),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                    val value = element as? XmlAttributeValue ?: return PsiReference.EMPTY_ARRAY
                    val site = FlowableXmlKeyCatalog.siteForAttributeValue(value) ?: return PsiReference.EMPTY_ARRAY
                    if (!FlowableXmlKeyCatalog.isResolvableKey(value.value)) return PsiReference.EMPTY_ARRAY
                    return arrayOf(FlowableXmlKeyReference(value, site))
                }
            },
        )
    }
}

/** Resolves an XML cross-reference key to the model file(s) that declare it. */
private class FlowableXmlKeyReference(
    value: XmlAttributeValue,
    private val site: XmlKeySite,
) : PsiReferenceBase.Poly<XmlAttributeValue>(value, ElementManipulators.getValueTextRange(value), false) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val key = element.value
        if (!FlowableXmlKeyCatalog.isResolvableKey(key)) return ResolveResult.EMPTY_ARRAY
        val psiManager = PsiManager.getInstance(element.project)
        return element.project.service<FlowableModelIndexService>().find(key)
            .filter { it.type in site.types }
            .mapNotNull { psiManager.findFile(it.file) }
            .map { PsiElementResolveResult(it) }
            .toTypedArray()
    }

    // Completion is handled by the dedicated XML contributor; don't duplicate variants here.
    override fun getVariants(): Array<Any> = emptyArray()
}
