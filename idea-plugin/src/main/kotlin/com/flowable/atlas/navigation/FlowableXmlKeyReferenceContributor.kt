package com.flowable.atlas.navigation

import com.flowable.atlas.completion.FlowableXmlKeyCatalog
import com.flowable.atlas.completion.FlowableXmlKeyCatalog.XmlKeySite
import com.flowable.atlas.completion.FlowableXmlKeyCatalog.XmlTextSite
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.intellij.openapi.components.service
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.XmlPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.util.ProcessingContext

/**
 * Turns a Flowable model-key reference inside model XML into a navigable reference: Ctrl-click jumps to
 * the referenced model at its key, and Find Usages works. Two shapes, both from [FlowableXmlKeyCatalog]:
 * an **attribute** (BPMN `calledElement`, `flowable:formKey`, CMMN `caseRef`/`processRef`/`decisionRef`, …)
 * and an **extension element's text** (`<flowable:eventType>`, `<flowable:channelKey>`,
 * `<design:sla-definition-key>`, …) — Design writes the latter as CDATA, which the value range skips.
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
        // An XmlText exposes no provider references of its own (only tags and attribute values do), so the
        // text's reference hangs off the *tag*, with the key's range inside the tag — the shape every
        // element-text reference in the platform takes.
        registrar.registerReferenceProvider(
            XmlPatterns.xmlTag(),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                    val tag = element as? XmlTag ?: return PsiReference.EMPTY_ARRAY
                    val site = FlowableXmlKeyCatalog.textSiteForTag(tag) ?: return PsiReference.EMPTY_ARRAY
                    val text = tag.value.textElements.singleOrNull() ?: return PsiReference.EMPTY_ARRAY
                    val inText = keyRangeIn(text) ?: return PsiReference.EMPTY_ARRAY
                    if (!FlowableXmlKeyCatalog.isResolvableKey(inText.substring(text.text))) return PsiReference.EMPTY_ARRAY
                    return arrayOf(FlowableXmlTextKeyReference(tag, inText.shiftRight(text.startOffsetInParent), site))
                }
            },
        )
    }

    companion object {
        /**
         * The range of the key inside [text]: the value (inside a CDATA section when there is one) with
         * the whitespace Design puts around it left out. Null when there is no text at all.
         */
        fun keyRangeIn(text: XmlText): TextRange? {
            val full = ElementManipulators.getValueTextRange(text)
            val raw = full.substring(text.text)
            if (raw.isBlank()) return null
            val lead = raw.length - raw.trimStart().length
            val trail = raw.length - raw.trimEnd().length
            return TextRange(full.startOffset + lead, full.endOffset - trail)
        }
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
        return resolveKey(element, key, site.types)
    }

    // Completion is handled by the dedicated XML contributor; don't duplicate variants here.
    override fun getVariants(): Array<Any> = emptyArray()
}

/**
 * The same, for an extension element's text. Soft: the broken-key inspection is the one that says
 * "unknown key" (with a quick fix, and only once keys of that type are indexed) — a hard reference would
 * paint a second, fix-less error under it.
 */
private class FlowableXmlTextKeyReference(
    tag: XmlTag,
    range: TextRange,
    private val site: XmlTextSite,
) : PsiReferenceBase.Poly<XmlTag>(tag, range, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        resolveKey(element, rangeInElement.substring(element.text), site.types)

    override fun getVariants(): Array<Any> = emptyArray()
}

/** A reference resolves under the read lock — the cached index only, never a build here. */
private fun resolveKey(element: PsiElement, key: String, types: Collection<ModelType>): Array<ResolveResult> {
    val index = element.project.service<FlowableModelIndexService>().cachedOrRequest() ?: return ResolveResult.EMPTY_ARRAY
    return ModelKeyTargets.resolve(element.project, index.find(key).filter { it.type in types })
}
