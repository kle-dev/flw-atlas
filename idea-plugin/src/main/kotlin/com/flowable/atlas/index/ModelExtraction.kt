package com.flowable.atlas.index

import com.flowable.atlas.model.JsonUtil
import com.flowable.atlas.model.ModelType
import java.io.ByteArrayInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * Extracts the model key(s) + name(s) + members from a model file's raw bytes.
 *
 *  - XML models (bpmn/cmmn/dmn): the `id`/`name` on every top-level `<process>` / `<case>` /
 *    `<decision>` element, plus the members declared inside it (variables, userTask/activity ids,
 *    DMN input/output variables) and the file-level BPMN `<message>` / `<signal>` names. Read with
 *    streaming StAX (JDK built-in) — namespaces are ignored via local names.
 *  - JSON models (everything else): top-level `key`/`name` (falling back to `metadata.key/name`),
 *    plus form field/outcome ids and event payload names via [JsonUtil].
 */
object ModelExtraction {

    private val XML_INPUT_FACTORY: XMLInputFactory = XMLInputFactory.newInstance().apply {
        // Harden against external entities / DTDs.
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
    }

    /** BPMN/CMMN flow-node local names whose `id` is an `activityId`. */
    private val FLOW_NODES = setOf(
        "task", "userTask", "serviceTask", "scriptTask", "sendTask", "receiveTask", "manualTask",
        "businessRuleTask", "subProcess", "transaction", "callActivity", "adhocSubProcess",
        "startEvent", "endEvent", "intermediateCatchEvent", "intermediateThrowEvent", "boundaryEvent",
        "exclusiveGateway", "parallelGateway", "inclusiveGateway", "eventBasedGateway", "complexGateway",
        // CMMN plan items / stages
        "planItem", "humanTask", "stage", "milestone", "processTask", "caseTask", "decisionTask",
    )

    fun extract(fileName: String, bytes: ByteArray, type: ModelType): List<RawModel> =
        if (ModelType.isXmlModel(fileName)) extractXml(bytes, type)
        else extractJson(bytes, type)

    private fun extractJson(bytes: ByteArray, type: ModelType): List<RawModel> {
        val raw = JsonUtil.extractKeyName(bytes) ?: return emptyList()
        val members = when (type) {
            ModelType.FORM -> {
                val fm = JsonUtil.readForm(bytes)
                ModelMembers(variables = fm.fields, formFields = fm.fields, formOutcomes = fm.outcomes)
            }
            ModelType.EVENT -> ModelMembers(payload = JsonUtil.readEventPayload(bytes))
            else -> ModelMembers.EMPTY
        }
        return listOf(raw.copy(members = members))
    }

    private fun xmlTagFor(type: ModelType): String? = when (type) {
        ModelType.PROCESS -> "process"
        ModelType.CASE -> "case"
        ModelType.DECISION -> "decision"
        else -> null
    }

    private class Builder(val id: String, val name: String?) {
        val variables = LinkedHashSet<String>()
        val userTaskIds = LinkedHashSet<String>()
        val activityIds = LinkedHashSet<String>()
        val decisionVariables = LinkedHashSet<String>()
    }

    private fun extractXml(bytes: ByteArray, type: ModelType): List<RawModel> {
        val tag = xmlTagFor(type) ?: return emptyList()
        val out = ArrayList<Builder>()
        val fileMessages = LinkedHashSet<String>()
        val fileSignals = LinkedHashSet<String>()
        var current: Builder? = null
        var inInputExpression = false
        var captureTextInto: LinkedHashSet<String>? = null

        ByteArrayInputStream(bytes).use { input ->
            val reader = XML_INPUT_FACTORY.createXMLStreamReader(input)
            try {
                while (reader.hasNext()) {
                    when (reader.next()) {
                        XMLStreamConstants.START_ELEMENT -> {
                            val ln = reader.localName
                            val model = current
                            when {
                                ln == tag -> {
                                    val id = attr(reader, "id")
                                    if (!id.isNullOrBlank()) {
                                        current = Builder(id, attr(reader, "name")).also { out.add(it) }
                                    }
                                }
                                ln == "message" -> attr(reader, "name")?.let { fileMessages.add(it) }
                                ln == "signal" -> attr(reader, "name")?.let { fileSignals.add(it) }
                                model != null -> {
                                    collectMember(reader, ln, model, type)
                                    if (type == ModelType.DECISION && ln == "inputExpression") inInputExpression = true
                                    if (ln == "text" && inInputExpression) captureTextInto = model.decisionVariables
                                }
                            }
                        }
                        XMLStreamConstants.CHARACTERS -> {
                            captureTextInto?.let { target ->
                                identifier(reader.text)?.let { target.add(it) }
                                captureTextInto = null
                            }
                        }
                        XMLStreamConstants.END_ELEMENT -> {
                            val ln = reader.localName
                            if (ln == tag) current = null
                            if (ln == "inputExpression") inInputExpression = false
                        }
                    }
                }
            } finally {
                reader.close()
            }
        }

        val messages = fileMessages.toList()
        val signals = fileSignals.toList()
        return out.map { b ->
            RawModel(
                b.id,
                b.name,
                ModelMembers(
                    variables = b.variables.toList(),
                    userTaskIds = b.userTaskIds.toList(),
                    activityIds = b.activityIds.toList(),
                    messages = messages,
                    signals = signals,
                    decisionVariables = b.decisionVariables.toList(),
                ),
            )
        }
    }

    private fun collectMember(reader: XMLStreamReader, ln: String, b: Builder, type: ModelType) {
        when (type) {
            ModelType.PROCESS, ModelType.CASE -> when (ln) {
                "userTask", "humanTask" -> attr(reader, "id")?.let { b.userTaskIds.add(it); b.activityIds.add(it) }
                "dataObject" -> (attr(reader, "name") ?: attr(reader, "id"))?.let { b.variables.add(it) }
                "formProperty" -> attr(reader, "id")?.let { b.variables.add(it) }
                in FLOW_NODES -> attr(reader, "id")?.let { b.activityIds.add(it) }
            }
            ModelType.DECISION -> when (ln) {
                "output" -> attr(reader, "name")?.let { b.decisionVariables.add(it) }
                "input" -> attr(reader, "label")?.let { identifier(it)?.let(b.decisionVariables::add) }
            }
            else -> {}
        }
    }

    private fun attr(reader: XMLStreamReader, localName: String): String? {
        for (i in 0 until reader.attributeCount) {
            if (reader.getAttributeLocalName(i) == localName) return reader.getAttributeValue(i)
        }
        return null
    }

    /** A trimmed value if it is a simple identifier (e.g. a DMN input-expression variable), else null. */
    private fun identifier(raw: String?): String? {
        val t = raw?.trim() ?: return null
        return if (t.isNotEmpty() && t.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) t else null
    }
}
