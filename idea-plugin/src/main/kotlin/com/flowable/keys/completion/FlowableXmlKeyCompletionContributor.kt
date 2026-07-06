package com.flowable.keys.completion

import com.flowable.keys.index.FlowableModelIndexService
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.components.service
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
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
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().inside(XmlAttributeValue::class.java), Provider())
    }

    private class Provider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            val attrValue = PsiTreeUtil.getParentOfType(parameters.position, XmlAttributeValue::class.java) ?: return
            val site = FlowableXmlKeyCatalog.siteForAttributeValue(attrValue) ?: return

            val service = parameters.position.project.service<FlowableModelIndexService>()
            val out = result.withPrefixMatcher(FlowableInfixMatcher(result.prefixMatcher.prefix))
            val seen = HashSet<String>()
            for (type in site.types) {
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
