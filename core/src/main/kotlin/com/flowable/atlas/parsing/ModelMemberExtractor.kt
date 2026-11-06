package com.flowable.atlas.parsing

import com.flowable.atlas.model.ModelType
import java.io.ByteArrayInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * Extracts the model key(s) + name(s) + members from a model file's raw bytes — the single per-file
 * extraction used by both the IDE's live index and any batch caller. Pure JDK (StAX + [ModelJsonReader]),
 * no I/O and no IntelliJ, so it runs unchanged over a `java.nio` walk or an IntelliJ VFS walk.
 *
 *  - XML models (bpmn/cmmn/dmn): the `id`/`name` on every top-level `<process>` / `<case>` /
 *    `<decision>` element, plus the members declared inside it (variables, userTask/activity ids,
 *    DMN input/output variables) and the file-level BPMN `<message>` / `<signal>` names. Read with
 *    streaming StAX (JDK built-in) — namespaces are ignored via local names.
 *  - JSON models (everything else): top-level `key`/`name` (falling back to `metadata.key/name`),
 *    plus form/page field ids, outcomes and bound variables, and event payload names via [ModelJsonReader].
 */
object ModelMemberExtractor {

    private val XML_INPUT_FACTORY: XMLInputFactory = XMLInputFactory.newInstance().apply {
        // Harden against external entities / DTDs.
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
    }

    /**
     * BPMN/CMMN flow-node local names whose `id` is an `activityId`. The IDE flags an activity id no scoped
     * model declares, so a missing name here is a false warning, not just a gap in completion: the sub-process
     * kinds come from the parser's own list (the spec spells `adHocSubProcess` with a capital H), so the IDE
     * and the graph agree on what one is called, and a case's event listeners are plan-item definitions that
     * `activePlanItemDefinitionId(…)` takes like any task.
     */
    private val FLOW_NODES = setOf(
        "task", "userTask", "serviceTask", "scriptTask", "sendTask", "receiveTask", "manualTask",
        "businessRuleTask", "callActivity",
        "startEvent", "endEvent", "intermediateCatchEvent", "intermediateThrowEvent", "boundaryEvent",
        "exclusiveGateway", "parallelGateway", "inclusiveGateway", "eventBasedGateway", "complexGateway",
        // CMMN plan items / stages
        "planItem", "humanTask", "stage", "milestone", "processTask", "caseTask", "decisionTask",
        "eventListener", "timerEventListener", "userEventListener", "genericEventListener",
        "signalEventListener", "variableEventListener", "intentEventListener", "reactivationEventListener",
    ) + XmlHelpers.BPMN_SUB_PROCESS_TAGS

    fun extract(fileName: String, bytes: ByteArray, type: ModelType): List<RawModel> =
        if (ModelType.isXmlModel(fileName)) extractXml(bytes, type)
        else extractJson(bytes, type)

    private fun extractJson(bytes: ByteArray, type: ModelType): List<RawModel> {
        val raw = ModelJsonReader.extractKeyName(bytes) ?: return emptyList()
        val members = when (type) {
            // A page is laid out of the same components, and a `{{…}}` inside it completes against the same
            // vocabulary — with no members of its own, completion in a page found nothing to offer.
            ModelType.FORM, ModelType.PAGE -> {
                val fm = ModelJsonReader.readForm(bytes)
                ModelMembers(variables = fm.variables, formFields = fm.fields, formOutcomes = fm.outcomes)
            }
            ModelType.EVENT -> ModelMembers(payload = ModelJsonReader.readEventPayload(bytes))
            // An action carries the `botKey` of the Java BotService it invokes — captured so the live
            // index can answer "which actions use this bot" and offer the key for search/filter.
            ModelType.ACTION -> ModelMembers(botKey = ModelJsonReader.topLevelString(bytes, "botKey"))
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
        // What an event definition refers to, and the ids it can refer to — see [eventNames].
        val messageIds = HashSet<String>()
        val signalIds = HashSet<String>()
        val messageRefs = LinkedHashSet<String>()
        val signalRefs = LinkedHashSet<String>()
        var dynamicMessages = false
        var dynamicSignals = false
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
                            // A case listens for a signal on an event listener's `flowable:signalRef`, and
                            // `runtimeService.signalEventReceived` reaches it as it reaches a process: the
                            // name is one of the project's signals. Read here, not in the `when`, which
                            // also has to record the listener as an activity.
                            if (type == ModelType.CASE && (ln == "eventListener" || ln.endsWith("EventListener"))) {
                                attr(reader, "signalRef")?.let { signalRefs.add(it) }
                            }
                            when {
                                ln == tag -> {
                                    val id = attr(reader, "id")
                                    if (!id.isNullOrBlank()) {
                                        current = Builder(id, attr(reader, "name")).also { out.add(it) }
                                    }
                                }
                                ln == "message" -> {
                                    attr(reader, "id")?.let { messageIds.add(it) }
                                    attr(reader, "name")?.let { fileMessages.add(it) }
                                }
                                ln == "signal" -> {
                                    attr(reader, "id")?.let { signalIds.add(it) }
                                    attr(reader, "name")?.let { fileSignals.add(it) }
                                }
                                ln == "messageEventDefinition" -> {
                                    attr(reader, "messageRef")?.let { messageRefs.add(it) }
                                    if (attr(reader, "messageExpression") != null) dynamicMessages = true
                                }
                                ln == "signalEventDefinition" -> {
                                    attr(reader, "signalRef")?.let { signalRefs.add(it) }
                                    if (attr(reader, "signalExpression") != null) dynamicSignals = true
                                }
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

        val messages = eventNames(fileMessages, messageIds, messageRefs)
        val signals = eventNames(fileSignals, signalIds, signalRefs)
        dynamicMessages = dynamicMessages || messages.any { isExpression(it) }
        dynamicSignals = dynamicSignals || signals.any { isExpression(it) }
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
                    dynamicMessages = dynamicMessages,
                    dynamicSignals = dynamicSignals,
                ),
            )
        }
    }

    /**
     * The names a file's messages or signals go by at run time: every declared `name`, plus each event
     * definition's ref that matches no declared `id`. The engine resolves a ref through the declaration
     * when there is one and otherwise takes the ref itself as the name (its message and signal parse
     * handlers do exactly that), so a bare `signalRef="orderCancelled"` is a signal called
     * `orderCancelled` — leaving it out would call every Java line sending it a typo. A `tns:` prefix
     * on a ref is the document's namespace, not part of the id; an undeclared prefixed ref counts under
     * both spellings, since whether the engine strips the prefix depends on the namespace it resolves to,
     * and an extra name only ever costs a verdict, never a false warning.
     */
    private fun eventNames(names: Set<String>, ids: Set<String>, refs: Set<String>): List<String> {
        val out = LinkedHashSet(names)
        for (ref in refs) {
            val bare = ref.substringAfter(':')
            if (ref.isBlank() || ref in ids || bare in ids) continue
            out.add(ref)
            if (bare.isNotBlank()) out.add(bare)
        }
        return out.toList()
    }

    private fun isExpression(name: String) = name.contains("\${") || name.contains("#{")

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
