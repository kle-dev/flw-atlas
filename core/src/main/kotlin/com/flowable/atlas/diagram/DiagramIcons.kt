package com.flowable.atlas.diagram

/**
 * Resolves a notation element to the type glyph it should carry, and to the name **Flowable Design**
 * gives that type.
 *
 * Four sources describe the same thing with decreasing precision, so they are consulted in that order:
 *
 *  1. **the event definition** (`timerEventDefinition`, `messageEventDefinition`, …) — for events this
 *     is the type, whatever the surrounding tag says;
 *  2. **the Design stencil id** — `<design:stencilid>` in an exported model's `extensionElements`, or
 *     `stencil.id` in a Design-workspace JSON. The most precise source there is: it distinguishes
 *     `ServiceRegistryTask` from `AgentTask` from `DataObjectCreateTask`, all of which are plain
 *     `<serviceTask>` elements in the XML;
 *  3. **`flowable:type`** on a service task (`agent`, `http`, `service-registry`, `dmn`, `mail`, …) —
 *     present in deployment XML that carries no Design extensions;
 *  4. **the XML tag / ORYX stencil name** (`userTask`, `scriptTask`, `callActivity`, …).
 *
 * Anything unrecognised resolves to `null`, and the painter draws the plain silhouette it drew before —
 * an unfamiliar element never disappears and never gets a wrong icon.
 *
 * The labels are Design's own, taken from its palette bundles (`stencil_translations_bpmn.properties`,
 * `stencil_translations_cmmn.properties` and the platform palette's `platform-translation.properties`),
 * so a diagram tooltip says exactly what the modeller sees in the Design palette.
 */
object DiagramIcons {

    /** The glyph and Design name for one element, or `(null, null)` when nothing is known about it. */
    fun resolve(stencil: String?, flowableType: String?, tag: String, eventDef: String?): Resolved {
        // an event's definition wins: <endEvent> with an errorEventDefinition is an "Error end event"
        eventDef?.let { def ->
            EVENT_DEFS[def.lowercase().removeSuffix("eventdefinition")]?.let { return it }
        }
        stencil?.lowercase()?.let { s -> STENCILS[s]?.let { return it } }
        flowableType?.lowercase()?.let { t -> FLOWABLE_TYPES[t]?.let { return it } }
        return TAGS[tag.lowercase()] ?: Resolved(null, null)
    }

    /** A resolved glyph plus the Design label for the element's type. */
    data class Resolved(val icon: DiaIcon?, val typeLabel: String?)

    private fun r(icon: DiaIcon?, label: String) = Resolved(icon, label)

    /** `<xxx>EventDefinition` (suffix already stripped) → glyph + Design name. */
    private val EVENT_DEFS: Map<String, Resolved> = mapOf(
        "timer" to r(DiaIcon.TIMER, "Timer event"),
        "message" to r(DiaIcon.MESSAGE, "Message event"),
        "signal" to r(DiaIcon.SIGNAL, "Signal event"),
        "error" to r(DiaIcon.ERROR, "Error event"),
        "escalation" to r(DiaIcon.ESCALATION, "Escalation event"),
        "terminate" to r(DiaIcon.TERMINATE, "Terminate end event"),
        "conditional" to r(DiaIcon.CONDITIONAL, "Conditional event"),
        "compensate" to r(DiaIcon.COMPENSATION, "Compensation event"),
        "compensation" to r(DiaIcon.COMPENSATION, "Compensation event"),
        "link" to r(DiaIcon.LINK, "Link event"),
    )

    /**
     * Design stencil id → glyph + Design palette title. Keys are lower-cased; both the BPMN and the CMMN
     * palettes are covered, plus the Work palette's platform activities.
     */
    private val STENCILS: Map<String, Resolved> = buildMap {
        // --- BPMN tasks (core palette) ---
        put("usertask", r(DiaIcon.USER, "User task"))
        put("formtask", r(DiaIcon.USER, "User task"))          // export normalises UserTask → FormTask
        put("servicetask", r(DiaIcon.SERVICE, "Service task"))
        put("scripttask", r(DiaIcon.SCRIPT, "Script task"))
        put("manualtask", r(DiaIcon.MANUAL, "Manual task"))
        put("businessrule", r(DiaIcon.BUSINESS_RULE, "Business rule task"))
        put("businessruletask", r(DiaIcon.BUSINESS_RULE, "Business rule task"))
        put("dmntask", r(DiaIcon.DECISION, "Decision task"))
        put("decisiontask", r(DiaIcon.DECISION, "Decision task"))
        put("mailtask", r(DiaIcon.MAIL, "Email task"))
        put("emailtask", r(DiaIcon.MAIL, "Email task"))
        put("httptask", r(DiaIcon.HTTP, "HTTP task"))
        put("shelltask", r(DiaIcon.SHELL, "Shell task"))
        put("cameltask", r(DiaIcon.CAMEL, "Camel task"))
        put("muletask", r(DiaIcon.SERVICE, "Mule task"))
        put("sendtask", r(DiaIcon.SEND, "Send task"))
        put("receivetask", r(DiaIcon.RECEIVE, "Receive task"))
        put("sendeventtask", r(DiaIcon.SEND_EVENT, "Send event task"))
        put("receiveeventtask", r(DiaIcon.RECEIVE_EVENT, "Receive event task"))
        put("sendandreceiveeventtask", r(DiaIcon.SEND_EVENT, "Send and receive event task"))
        put("externalworkertask", r(DiaIcon.EXTERNAL_WORKER, "External Worker task"))
        put("casetask", r(DiaIcon.CASE, "Case task"))
        put("callactivity", r(null, "Call activity"))              // silhouette carries the meaning
        put("subprocess", r(null, "Sub-process"))
        put("expandedsubprocess", r(null, "Sub-process"))
        put("collapsedsubprocess", r(null, "Collapsed subprocess"))
        put("eventsubprocess", r(null, "Event sub-process"))
        put("adhocsubprocess", r(null, "Adhoc subprocess"))
        // --- Work palette (platform activities) ---
        put("serviceregistrytask", r(DiaIcon.SERVICE_REGISTRY, "Service registry task"))
        put("agenttask", r(DiaIcon.AGENT, "AI Agent"))
        put("dataobjectcreatetask", r(DiaIcon.DATA_OBJECT, "Data object create"))
        put("dataobjectlookuptask", r(DiaIcon.DATA_OBJECT, "Data object lookup"))
        put("dataobjectupdatetask", r(DiaIcon.DATA_OBJECT, "Data object update"))
        put("dataobjectdeletetask", r(DiaIcon.DATA_OBJECT, "Data object delete"))
        put("dataobjectsearchtask", r(DiaIcon.DATA_OBJECT, "Data object search"))
        put("generatedocumenttask", r(DiaIcon.DOCUMENT, "Generate Document"))
        put("createdocumenttask", r(DiaIcon.DOCUMENT, "Create document"))
        put("mergedocumenttask", r(DiaIcon.DOCUMENT, "Merge document"))
        put("converddocumenttopdftask", r(DiaIcon.DOCUMENT, "Convert document to PDF"))
        put("generatesequencetask", r(DiaIcon.SEQUENCE, "Generate Sequence Task"))
        put("variableactivity", r(DiaIcon.INIT_VARIABLES, "Initialize variables"))
        put("audittask", r(DiaIcon.AUDIT, "Audit"))
        put("housekeepingtask", r(DiaIcon.SERVICE, "Housekeeping"))
        // --- CMMN plan items ---
        put("humantask", r(DiaIcon.USER, "Human task"))
        put("processtask", r(DiaIcon.PROCESS, "Process task"))
        put("planmodel", r(null, "Case plan model"))
        put("caseplanmodel", r(null, "Case plan model"))
        put("stage", r(null, "Stage"))
        put("milestone", r(null, "Milestone"))
    }

    /** `flowable:type` on a `<serviceTask>` → glyph + Design name (deployment XML without Design ext). */
    private val FLOWABLE_TYPES: Map<String, Resolved> = mapOf(
        "http" to r(DiaIcon.HTTP, "HTTP task"),
        "mail" to r(DiaIcon.MAIL, "Email task"),
        "dmn" to r(DiaIcon.DECISION, "Decision task"),
        "camel" to r(DiaIcon.CAMEL, "Camel task"),
        "mule" to r(DiaIcon.SERVICE, "Mule task"),
        "shell" to r(DiaIcon.SHELL, "Shell task"),
        "script" to r(DiaIcon.SCRIPT, "Script task"),
        "case" to r(DiaIcon.CASE, "Case task"),
        "external-worker" to r(DiaIcon.EXTERNAL_WORKER, "External Worker task"),
        "send-event" to r(DiaIcon.SEND_EVENT, "Send event task"),
        "sendevent" to r(DiaIcon.SEND_EVENT, "Send event task"),
        "service-registry" to r(DiaIcon.SERVICE_REGISTRY, "Service registry task"),
        "agent" to r(DiaIcon.AGENT, "AI Agent"),
        "data-object" to r(DiaIcon.DATA_OBJECT, "Data object task"),
        "init-variables" to r(DiaIcon.INIT_VARIABLES, "Initialize variables"),
        "audit" to r(DiaIcon.AUDIT, "Audit"),
        "generate-document" to r(DiaIcon.DOCUMENT, "Generate Document"),
        "create-document" to r(DiaIcon.DOCUMENT, "Create document"),
        "convert-document-to-pdf" to r(DiaIcon.DOCUMENT, "Convert document to PDF"),
        "java" to r(DiaIcon.SERVICE, "Service task"),
    )

    /** XML local tag → glyph + Design name; the last resort, and the only source for plain BPMN. */
    private val TAGS: Map<String, Resolved> = mapOf(
        "usertask" to r(DiaIcon.USER, "User task"),
        "servicetask" to r(DiaIcon.SERVICE, "Service task"),
        "scripttask" to r(DiaIcon.SCRIPT, "Script task"),
        "manualtask" to r(DiaIcon.MANUAL, "Manual task"),
        "businessruletask" to r(DiaIcon.BUSINESS_RULE, "Business rule task"),
        "sendtask" to r(DiaIcon.SEND, "Send task"),
        "receivetask" to r(DiaIcon.RECEIVE, "Receive task"),
        "task" to r(null, "Task"),
        "callactivity" to r(null, "Call activity"),
        "subprocess" to r(null, "Sub-process"),
        "transaction" to r(null, "Transaction sub-process"),
        "adhocsubprocess" to r(null, "Adhoc subprocess"),
        "humantask" to r(DiaIcon.USER, "Human task"),
        "processtask" to r(DiaIcon.PROCESS, "Process task"),
        "casetask" to r(DiaIcon.CASE, "Case task"),
        "decisiontask" to r(DiaIcon.DECISION, "Decision task"),
        "milestone" to r(null, "Milestone"),
        "stage" to r(null, "Stage"),
        "caseplanmodel" to r(null, "Case plan model"),
        "entrycriterion" to r(null, "Entry criterion"),
        "exitcriterion" to r(null, "Exit criterion"),
        "startevent" to r(null, "Start event"),
        "endevent" to r(null, "End event"),
        "boundaryevent" to r(null, "Boundary event"),
        "intermediatecatchevent" to r(null, "Intermediate catching event"),
        "intermediatethrowevent" to r(null, "Intermediate throwing event"),
        "exclusivegateway" to r(null, "Exclusive gateway"),
        "parallelgateway" to r(null, "Parallel gateway"),
        "inclusivegateway" to r(null, "Inclusive gateway"),
        "eventbasedgateway" to r(null, "Event gateway"),
        "dataobject" to r(null, "Data object"),
        "datastore" to r(null, "Data store"),
        "textannotation" to r(null, "Text annotation"),
        "lane" to r(null, "Lane"),
        "participant" to r(null, "Pool"),
        // DMN / DRD — Design's own DRD palette wording
        "decision" to r(null, "Decision"),
        "decisionservice" to r(null, "Decision service"),
        "expandeddecisionservice" to r(null, "Decision service"),
        "outputdecisionsdecisionservicesection" to r(null, "Output decisions section"),
        "encapsulateddecisionsdecisionservicesection" to r(null, "Encapsulated decisions section"),
        "inputdata" to r(null, "Input data"),
        "businessknowledgemodel" to r(null, "Business knowledge model"),
        "knowledgesource" to r(null, "Knowledge source"),
    )
}
