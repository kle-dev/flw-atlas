package com.flowable.atlas.parsing

import com.flowable.atlas.model.ModelType

/**
 * The canonical model-kind registry — a port of `flowable_atlas.py` (`model_type_for`, `MODEL_KINDS`,
 * ~lines 52-129 and 1055-1086). [EXT_TO_TYPE] is derived from [ModelType.EXTENSIONS]; the directory
 * and archive rules live in [com.flowable.atlas.model.ModelPaths].
 *
 * [MODEL_KINDS] is THE single place tying a parsed model type to its result bucket and graph node
 * type; the bucket lists, dedupe, reference resolution and graph building all derive from it, so
 * adding a model kind is exactly one entry here (+ one in [ModelParsers.PARSERS]).
 */
object ModelKinds {

    /**
     * File-extension → model type (parser key). Derived from the single canonical registry
     * [ModelType.EXTENSIONS] via [ModelType.parserKey] (bpmn/cmmn/dmn stay pre-normalized here).
     * Keys are lowercase; matched case-insensitively by [modelTypeFor].
     */
    val EXT_TO_TYPE: Map<String, String> =
        ModelType.EXTENSIONS.associate { (ext, type) -> ext to type.parserKey }

    /** The parser-key model type of a file by its name, or null if it is not a known model. */
    fun modelTypeFor(filename: String): String? = ModelType.byExtension(filename)?.parserKey

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
