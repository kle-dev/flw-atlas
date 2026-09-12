package com.flowable.atlas.navigation

import com.flowable.atlas.completion.FlowableJsonKeyCatalog
import com.flowable.atlas.completion.FlowableXmlKeyCatalog
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.usage.ModelKeyFindUsagesHandlerFactory
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag

/**
 * A model key **inside a model file**, found on the PSI — what hover, the name inlay and the diagram
 * gutter all need to agree on. Three shapes of cross-reference (an XML attribute, an extension
 * element's text, a JSON string — the catalogs [FlowableXmlKeyCatalog] and [FlowableJsonKeyCatalog])
 * and one of declaration: the model's own key in its own file, as Find Usages recognises it.
 */
object ModelFileKeySites {

    /**
     * A key at one place in a model file: the [key], the model [types] it may name, the absolute
     * [range] of its text, and whether it is the file's own [declaration] rather than a reference.
     */
    data class Site(val key: String, val types: Collection<ModelType>, val range: TextRange, val declaration: Boolean)

    /** The site [element] itself is — an attribute value, a tag whose text is a key, a JSON string — or null. */
    fun siteOf(element: PsiElement): Site? = when (element) {
        is XmlAttributeValue -> attributeSite(element)
        is XmlTag -> tagTextSite(element)
        is JsonStringLiteral -> jsonSite(element)
        else -> null
    }

    /** The site around [element] — a token under the caret, a line-marker anchor — walking up to it, or null. */
    fun at(element: PsiElement?): Site? {
        var e = element
        while (e != null && e !is PsiFile) {
            siteOf(e)?.let { return it }
            e = e.parent
        }
        return null
    }

    private fun attributeSite(value: XmlAttributeValue): Site? {
        val key = value.value
        if (!FlowableXmlKeyCatalog.isResolvableKey(key)) return null
        val range = ElementManipulators.getValueTextRange(value).shiftRight(value.textRange.startOffset)
        FlowableXmlKeyCatalog.siteForAttributeValue(value)?.let { return Site(key, it.types, range, declaration = false) }
        val decl = ModelKeyFindUsagesHandlerFactory.declarationOf(value) ?: return null
        return Site(decl.key, listOf(decl.type), range, declaration = true)
    }

    private fun tagTextSite(tag: XmlTag): Site? {
        val site = FlowableXmlKeyCatalog.textSiteForTag(tag) ?: return null
        val text = tag.value.textElements.singleOrNull() ?: return null
        val inText = FlowableXmlKeyReferenceContributor.keyRangeIn(text) ?: return null
        val key = inText.substring(text.text)
        if (!FlowableXmlKeyCatalog.isResolvableKey(key)) return null
        return Site(key, site.types, inText.shiftRight(text.textRange.startOffset), declaration = false)
    }

    private fun jsonSite(literal: JsonStringLiteral): Site? {
        val key = literal.value
        if (!FlowableXmlKeyCatalog.isResolvableKey(key) || key.contains("{{")) return null
        val range = ElementManipulators.getValueTextRange(literal).shiftRight(literal.textRange.startOffset)
        FlowableJsonKeyCatalog.siteForLiteral(literal)?.let { return Site(key, it.types, range, declaration = false) }
        val decl = ModelKeyFindUsagesHandlerFactory.declarationOf(literal) ?: return null
        return Site(decl.key, listOf(decl.type), range, declaration = true)
    }
}
