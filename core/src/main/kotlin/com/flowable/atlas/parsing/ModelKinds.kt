package com.flowable.atlas.parsing

/**
 * Discovery configuration + the canonical model-kind registry — a direct port of `flowable_atlas.py`
 * (`EXT_TO_TYPE`, `model_type_for`, `EXCLUDE_DIRS`, `ARCHIVE_EXTS`, `MODEL_KINDS`, ~lines 52-129 and
 * 1055-1086).
 *
 * [MODEL_KINDS] is THE single place tying a parsed model type to its result bucket and graph node
 * type; the bucket lists, dedupe, reference resolution and graph building all derive from it, so
 * adding a model kind is exactly one entry here (+ one in [ModelParsers.PARSERS]).
 */
object ModelKinds {

    /** File-extension → model type (parser key). Keys are lowercase; matched case-insensitively. */
    val EXT_TO_TYPE: Map<String, String> = linkedMapOf(
        ".app" to "app", ".bpmn" to "bpmn", ".bpmn20.xml" to "bpmn", ".cmmn" to "cmmn",
        ".cmmn.xml" to "cmmn", ".dmn" to "dmn", ".dmn.xml" to "dmn", ".form" to "form",
        ".page" to "page", ".data" to "dataObject", ".dictionary" to "dataDictionary",
        ".query" to "query", ".sequence" to "sequence", ".sla" to "sla", ".event" to "event",
        ".channel" to "channel", ".agent" to "agent", ".service" to "service",
        ".action" to "action", ".tpl" to "template", ".policy" to "securityPolicy",
        ".extractor" to "variableExtractor", ".knowledgebase" to "knowledgeBase",
        ".palette" to "palette", ".masterdata" to "masterData",
        ".dashboardcomponent" to "dashboardComponent", ".document" to "document",
    )

    val ARCHIVE_EXTS = listOf(".zip", ".bar")

    val EXCLUDE_DIRS = setOf(
        "target", "build", "node_modules", ".git", ".idea", ".gradle", "dist",
        "out", "bin", ".mvn", "coverage", "__pycache__", ".vscode", "test-classes",
    )

    /** Compound suffixes matched before the single final extension. */
    private val COMPOUND_EXTS = listOf(".bpmn20.xml", ".cmmn.xml", ".dmn.xml")

    /** The parser-key model type of a file by its name, or null if it is not a known model. */
    fun modelTypeFor(filename: String): String? {
        val low = filename.lowercase()
        for (ext in COMPOUND_EXTS) if (low.endsWith(ext)) return EXT_TO_TYPE[ext]
        val dot = filename.lastIndexOf('.')
        if (dot == -1) return null
        return EXT_TO_TYPE[low.substring(dot)]
    }

    /** (model type, result bucket, normalized node type) — declaration order is significant. */
    data class Kind(val mtype: String, val bucket: String, val nodeType: String)

    val MODEL_KINDS: List<Kind> = listOf(
        Kind("app", "apps", "app"),
        Kind("bpmn", "processes", "process"),
        Kind("cmmn", "cases", "case"),
        Kind("dmn", "decisions", "decision"),
        Kind("form", "forms", "form"),
        Kind("page", "forms", "page"),
        Kind("agent", "agents", "agent"),
        Kind("service", "services", "service"),
        Kind("channel", "channels", "channel"),
        Kind("event", "events", "event"),
        Kind("dataDictionary", "dictionaries", "dataDictionary"),
        Kind("dataObject", "dataObjects", "dataObject"),
        Kind("securityPolicy", "policies", "securityPolicy"),
        Kind("action", "actions", "action"),
    )

    /** model type → result bucket. */
    val MODEL_BUCKET: Map<String, String> = MODEL_KINDS.associate { it.mtype to it.bucket }

    /** Unique bucket names in declaration order, plus the two non-parser buckets. */
    val MODEL_BUCKETS: List<String> =
        MODEL_KINDS.map { it.bucket }.distinct() + listOf("liquibase", "others")

    /** Model types whose graph/index type differs from the parser key (bpmn→process, cmmn→case, …). */
    val NORMALIZE_TYPE: Map<String, String> =
        MODEL_KINDS.filter { it.mtype != it.nodeType }.associate { it.mtype to it.nodeType }
}
