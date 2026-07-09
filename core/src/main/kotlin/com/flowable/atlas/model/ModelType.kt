package com.flowable.atlas.model

/**
 * The Flowable model types a key can point at.
 *
 * The [id] strings and the extension / design-folder mappings below are a direct port of
 * `flowable_atlas.py` (`EXT_TO_TYPE`, ~line 49) plus the Flowable Design workspace layout
 * observed in real Flowable projects. bpmn/cmmn/dmn are normalized to
 * PROCESS/CASE/DECISION to match the Flowable public-API vocabulary used by the API catalog.
 */
enum class ModelType(val id: String, val display: String) {
    PROCESS("process", "Process"),
    CASE("case", "Case"),
    DECISION("decision", "Decision"),
    FORM("form", "Form"),
    PAGE("page", "Page"),
    DATA_OBJECT("dataObject", "Data Object"),
    DATA_DICTIONARY("dataDictionary", "Data Dictionary"),
    QUERY("query", "Query"),
    SEQUENCE("sequence", "Sequence"),
    SLA("sla", "SLA"),
    EVENT("event", "Event"),
    CHANNEL("channel", "Channel"),
    AGENT("agent", "Agent"),
    SERVICE("service", "Service"),
    ACTION("action", "Action"),
    TEMPLATE("template", "Template"),
    SECURITY_POLICY("securityPolicy", "Security Policy"),
    VARIABLE_EXTRACTOR("variableExtractor", "Variable Extractor"),
    KNOWLEDGE_BASE("knowledgeBase", "Knowledge Base"),
    MASTER_DATA("masterData", "Master Data"),
    DASHBOARD_COMPONENT("dashboardComponent", "Dashboard Component"),
    DOCUMENT("document", "Document"),
    PALETTE("palette", "Palette"),
    APP("app", "App");

    /**
     * The batch pipeline's parser-key (pre-normalization): `bpmn`/`cmmn`/`dmn` for the XML models,
     * otherwise the same as [id]. This is the single source for [parsing.ModelKinds.EXT_TO_TYPE].
     */
    val parserKey: String
        get() = when (this) {
            PROCESS -> "bpmn"
            CASE -> "cmmn"
            DECISION -> "dmn"
            else -> id
        }

    companion object {
        /**
         * Deployment-artifact extensions → type. Compound suffixes (`.bpmn20.xml`) come first so
         * [byExtension] matches them before the single-segment `.bpmn`. This is THE canonical
         * extension registry; [parsing.ModelKinds] derives its parser-key table from it.
         */
        val EXTENSIONS: List<Pair<String, ModelType>> = listOf(
            ".bpmn20.xml" to PROCESS, ".cmmn.xml" to CASE, ".dmn.xml" to DECISION,
            ".bpmn" to PROCESS, ".cmmn" to CASE, ".dmn" to DECISION,
            ".form" to FORM, ".page" to PAGE, ".data" to DATA_OBJECT,
            ".dictionary" to DATA_DICTIONARY, ".query" to QUERY, ".sequence" to SEQUENCE,
            ".sla" to SLA, ".event" to EVENT, ".channel" to CHANNEL, ".agent" to AGENT,
            ".service" to SERVICE, ".action" to ACTION, ".tpl" to TEMPLATE,
            ".policy" to SECURITY_POLICY, ".extractor" to VARIABLE_EXTRACTOR,
            ".knowledgebase" to KNOWLEDGE_BASE, ".masterdata" to MASTER_DATA,
            ".dashboardcomponent" to DASHBOARD_COMPONENT, ".document" to DOCUMENT,
            ".palette" to PALETTE, ".app" to APP,
        )

        /** Flowable Design workspace subfolders (they hold per-model `.json` files) → type. */
        private val DESIGN_FOLDERS: Map<String, ModelType> = mapOf(
            "bpmn-models" to PROCESS, "cmmn-models" to CASE,
            "decision-table-models" to DECISION, "dmn-models" to DECISION,
            "form-models" to FORM, "page-models" to PAGE,
            "data-object-models" to DATA_OBJECT, "data-dictionary-models" to DATA_DICTIONARY,
            "query-models" to QUERY, "sequence-models" to SEQUENCE, "event-models" to EVENT,
            "channel-models" to CHANNEL, "agent-models" to AGENT, "service-models" to SERVICE,
            "action-models" to ACTION, "template-models" to TEMPLATE,
            "security-policy-models" to SECURITY_POLICY,
            "variable-extractor-models" to VARIABLE_EXTRACTOR, "document-models" to DOCUMENT,
        )

        /** The three compound XML suffixes (matched before their single-segment forms). */
        val COMPOUND_EXTENSIONS: List<String> = listOf(".bpmn20.xml", ".cmmn.xml", ".dmn.xml")

        private val XML_EXTENSIONS = COMPOUND_EXTENSIONS + listOf(".bpmn", ".cmmn", ".dmn")

        /** Type of a deployment artifact by its file name, or null if not a known model extension. */
        fun byExtension(fileName: String): ModelType? {
            val n = fileName.lowercase()
            return EXTENSIONS.firstOrNull { n.endsWith(it.first) }?.second
        }

        /** Type of a Design-workspace `.json` file by its containing `*-models` folder name. */
        fun byDesignFolder(folderName: String?): ModelType? =
            folderName?.let { DESIGN_FOLDERS[it] }

        /** True if the file is an XML-serialized model (bpmn/cmmn/dmn) rather than JSON. */
        fun isXmlModel(fileName: String): Boolean {
            val n = fileName.lowercase()
            return XML_EXTENSIONS.any { n.endsWith(it) }
        }
    }
}
