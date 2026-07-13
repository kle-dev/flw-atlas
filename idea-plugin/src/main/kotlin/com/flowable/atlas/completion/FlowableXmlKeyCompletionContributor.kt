package com.flowable.atlas.completion

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.components.service
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.util.ProcessingContext

/**
 * Completes Flowable model keys inside a model-XML cross-reference attribute — e.g. a BPMN
 * `callActivity calledElement="<caret>"` offers process keys, `flowable:formKey="<caret>"` offers
 * form keys, `decisionRef` / `caseRef` / `processRef` the matching type. See [FlowableXmlKeyCatalog].
 *
 * Like the Java completion, candidates are searchable by key OR name and match on any infix
 * ([FlowableInfixMatcher]), so `0061` finds `KYC-DO-0061`.
 */
class FlowableXmlKeyCompletionContributor : CompletionContributor() {

    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().inside(XmlAttributeValue::class.java), AttributeProvider())
        // model keys as extension-element TEXT: <flowable:eventType>, <flowable:channelKey>, …
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().inside(XmlText::class.java), TextProvider())
    }

    private class AttributeProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            val attrValue = PsiTreeUtil.getParentOfType(parameters.position, XmlAttributeValue::class.java) ?: return
            val attribute = attrValue.parent as? XmlAttribute
            val service = parameters.position.project.service<FlowableModelIndexService>()

            // event payload field position (eventInParameter@target / eventOutParameter@source /
            // eventCorrelationParameter@name) — offer the sibling event's payload names
            val eventKey = attribute?.let { FlowableXmlKeyCatalog.eventKeyForPayloadAttribute(it) }
            if (eventKey != null) {
                val out = result.withPrefixMatcher(FlowableInfixMatcher(result.prefixMatcher.prefix))
                for (p in service.payloadOf(eventKey)) {
                    out.addElement(LookupElementBuilder.create(p).withTypeText("payload · $eventKey", true))
                }
                return
            }

            val site = FlowableXmlKeyCatalog.siteForAttributeValue(attrValue) ?: return
            addKeys(result, service, site.types)
        }
    }

    private class TextProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            val tag = PsiTreeUtil.getParentOfType(parameters.position, XmlTag::class.java) ?: return
            val site = FlowableXmlKeyCatalog.textSiteForTag(tag) ?: return
            val service = parameters.position.project.service<FlowableModelIndexService>()
            addKeys(result, service, site.types)
        }
    }

    private companion object {
        fun addKeys(result: CompletionResultSet, service: FlowableModelIndexService, types: List<ModelType>) {
            val out = result.withPrefixMatcher(FlowableInfixMatcher(result.prefixMatcher.prefix))
            val seen = HashSet<String>()
            for (type in types) {
                for (entry in service.keysOfType(type)) {
                    if (!seen.add(entry.key)) continue
                    var b = LookupElementBuilder.create(entry.key).withTypeText(entry.type.display, true)
                    if (entry.name != entry.key) b = b.withTailText("  ${entry.name}", true)
                    for (token in KeyLookup.searchTokens(entry.key, entry.name)) b = b.withLookupString(token)
                    out.addElement(b)
                }
            }
        }
    }
}
