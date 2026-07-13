package com.flowable.atlas.completion

import com.flowable.atlas.model.ModelType
import com.flowable.atlas.model.ModelType.CASE
import com.flowable.atlas.model.ModelType.CHANNEL
import com.flowable.atlas.model.ModelType.DECISION
import com.flowable.atlas.model.ModelType.EVENT
import com.flowable.atlas.model.ModelType.FORM
import com.flowable.atlas.model.ModelType.PROCESS
import com.flowable.atlas.model.ModelType.SECURITY_POLICY
import com.flowable.atlas.model.ModelType.SEQUENCE
import com.flowable.atlas.model.ModelType.SLA
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

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

    /** An XML attribute whose value is a model key of one of [types]. [elements] (local names)
     *  restricts the site to specific host elements — a `decisionRef` on some unrelated element
     *  must not be validated as a decision key. Null = any element. */
    data class XmlKeySite(
        val attributeLocalName: String,
        val types: List<ModelType>,
        val elements: Set<String>? = null,
    )

    private val sites: List<XmlKeySite> = listOf(
        XmlKeySite("calledElement", listOf(PROCESS), setOf("callActivity")),  // BPMN callActivity
        // CMMN processTask only — a BPMN <participant processRef> references an in-file id
        XmlKeySite("processRef", listOf(PROCESS), setOf("processTask")),
        XmlKeySite("caseRef", listOf(CASE), setOf("caseTask")),               // CMMN caseTask
        XmlKeySite("decisionRef", listOf(DECISION), setOf("decisionTask", "businessRuleTask")),
        XmlKeySite("decisionTableReferenceKey", listOf(DECISION), setOf("serviceTask", "businessRuleTask")),
        XmlKeySite("decisionServiceReferenceKey", listOf(DECISION), setOf("serviceTask", "businessRuleTask")),
        // case service task starts a CMMN case by definition key
        XmlKeySite("caseDefinitionKey", listOf(CASE), setOf("serviceTask")),
        XmlKeySite(
            "formKey", listOf(FORM),
            setOf("userTask", "startEvent", "humanTask", "casePlanModel", "task", "serviceTask", "humanTaskWithService", "planItem"),
        ),
    )

    private val byLocalName: Map<String, XmlKeySite> = sites.associateBy { it.attributeLocalName }

    /** The site for a raw (possibly namespaced) attribute name, or null. */
    fun siteForAttributeName(qualifiedName: String): XmlKeySite? = byLocalName[localName(qualifiedName)]

    /** The site for an attribute, but only when it lives in a BPMN/CMMN/DMN model file and sits
     *  on one of the site's host elements. */
    fun siteForAttribute(attribute: XmlAttribute): XmlKeySite? {
        val file = attribute.containingFile as? XmlFile ?: return null
        if (!ModelType.isXmlModel(file.name)) return null
        val site = siteForAttributeName(attribute.name) ?: return null
        val hostElements = site.elements ?: return site
        val host = attribute.parent?.name?.let(::localName) ?: return null
        return if (host in hostElements) site else null
    }

    fun siteForAttributeValue(value: XmlAttributeValue): XmlKeySite? =
        (value.parent as? XmlAttribute)?.let { siteForAttribute(it) }

    // ---- extension ELEMENTS whose text content is a model key --------------------------------

    /** An extension element (e.g. `<flowable:eventType>`) whose TEXT is a model key of [types]. */
    data class XmlTextSite(val elementLocalName: String, val types: List<ModelType>)

    private val textSites: Map<String, XmlTextSite> = listOf(
        XmlTextSite("eventType", listOf(EVENT)),            // event-registry start/receive/send
        XmlTextSite("triggerEventType", listOf(EVENT)),     // send-and-receive trigger event
        XmlTextSite("channelKey", listOf(CHANNEL)),         // explicit channel on send/receive
        XmlTextSite("processSequence", listOf(SEQUENCE)),
        XmlTextSite("caseSequence", listOf(SEQUENCE)),
        XmlTextSite("sla-definition-key", listOf(SLA)),
        XmlTextSite("security-policy-model", listOf(SECURITY_POLICY)),
    ).associateBy { it.elementLocalName }

    /** The text site for a tag, but only inside a BPMN/CMMN/DMN model file. */
    fun textSiteForTag(tag: XmlTag): XmlTextSite? {
        val file = tag.containingFile as? XmlFile ?: return null
        if (!ModelType.isXmlModel(file.name)) return null
        return textSites[localName(tag.name)]
    }

    // ---- event payload positions --------------------------------------------------------------

    /** Attribute positions whose value is an event PAYLOAD FIELD of the sibling `<eventType>`:
     *  send side maps variable→payload (`eventInParameter@target`), receive side payload→variable
     *  (`eventOutParameter@source`), correlation by payload name (`eventCorrelationParameter@name`). */
    private val payloadAttrByElement: Map<String, String> = mapOf(
        "eventInParameter" to "target",
        "eventOutParameter" to "source",
        "eventCorrelationParameter" to "name",
    )

    /** The event key governing [attribute] when it is a payload-field position, else null. */
    fun eventKeyForPayloadAttribute(attribute: XmlAttribute): String? {
        val file = attribute.containingFile as? XmlFile ?: return null
        if (!ModelType.isXmlModel(file.name)) return null
        val host = attribute.parent ?: return null
        val expected = payloadAttrByElement[localName(host.name)] ?: return null
        if (localName(attribute.name) != expected) return null
        // sibling <flowable:eventType> inside the same extensionElements block
        val container = host.parentTag ?: return null
        val eventType = container.subTags.firstOrNull { localName(it.name) == "eventType" } ?: return null
        return eventType.value.trimmedText.ifBlank { null }
    }

    /** A value we should NOT treat as a key: empty, or an EL/expression like `${var}` / `#{bean}`. */
    fun isResolvableKey(value: String): Boolean =
        value.isNotBlank() && !value.contains("\${") && !value.contains("#{")

    private fun localName(qualifiedName: String): String = qualifiedName.substringAfterLast(':')
}
