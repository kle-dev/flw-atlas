package com.flowable.atlas.completion

import com.flowable.atlas.model.ModelType
import com.flowable.atlas.model.ModelType.CASE
import com.flowable.atlas.model.ModelType.DECISION
import com.flowable.atlas.model.ModelType.FORM
import com.flowable.atlas.model.ModelType.PROCESS
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile

/**
 * The catalog of **model → model** key references that live inside Flowable model XML itself
 * (BPMN / CMMN), rather than in Java. A `callActivity calledElement="OTHER-PROC"` references a
 * process key; `flowable:formKey`, `decisionRef`, `caseRef`, … reference forms / decisions / cases.
 *
 * Matched by the attribute's **local name** (namespace-agnostic, so `flowable:formKey` and a bare
 * `formKey` both match), and only inside a BPMN/CMMN/DMN model file — so unrelated XML is untouched.
 * Used by the XML completion, reference and broken-key contributors.
 */
object FlowableXmlKeyCatalog {

    /** An XML attribute whose value is a model key of one of [types]. */
    data class XmlKeySite(val attributeLocalName: String, val types: List<ModelType>)

    private val sites: List<XmlKeySite> = listOf(
        XmlKeySite("calledElement", listOf(PROCESS)),          // BPMN callActivity
        XmlKeySite("processRef", listOf(PROCESS)),             // CMMN processTask
        XmlKeySite("caseRef", listOf(CASE)),                   // CMMN caseTask
        XmlKeySite("decisionRef", listOf(DECISION)),           // BPMN/CMMN decision reference
        XmlKeySite("decisionTableReferenceKey", listOf(DECISION)), // flowable:businessRuleTask
        XmlKeySite("formKey", listOf(FORM)),                   // flowable:formKey (userTask/startEvent/humanTask)
    )

    private val byLocalName: Map<String, XmlKeySite> = sites.associateBy { it.attributeLocalName }

    /** The site for a raw (possibly namespaced) attribute name, or null. */
    fun siteForAttributeName(qualifiedName: String): XmlKeySite? = byLocalName[localName(qualifiedName)]

    /** The site for an attribute, but only when it lives in a BPMN/CMMN/DMN model file. */
    fun siteForAttribute(attribute: XmlAttribute): XmlKeySite? {
        val file = attribute.containingFile as? XmlFile ?: return null
        if (!ModelType.isXmlModel(file.name)) return null
        return siteForAttributeName(attribute.name)
    }

    fun siteForAttributeValue(value: XmlAttributeValue): XmlKeySite? =
        (value.parent as? XmlAttribute)?.let { siteForAttribute(it) }

    /** A value we should NOT treat as a key: empty, or an EL/expression like `${var}` / `#{bean}`. */
    fun isResolvableKey(value: String): Boolean =
        value.isNotBlank() && !value.contains("\${") && !value.contains("#{")

    private fun localName(qualifiedName: String): String = qualifiedName.substringAfterLast(':')
}
