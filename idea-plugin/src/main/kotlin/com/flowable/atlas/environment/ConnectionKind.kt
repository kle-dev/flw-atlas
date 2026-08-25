package com.flowable.atlas.environment

/**
 * What a connection in an [AtlasEnvironments] catalog talks to. The two kinds are deliberately
 * separate records rather than two URL fields on one "environment" row: an environment legitimately
 * has a runtime app and no Design server (a QA stage nobody models against), or a Design server and
 * no running app, and a single record with both fields would have to encode "absent" as a blank
 * string in every consumer.
 *
 * Persisted **by name** as a plain `String` on [AtlasEnvironments.ConnectionState] — see the comment
 * there for why the field is not typed as this enum.
 */
enum class ConnectionKind(val display: String) {

    /** A Flowable Design server — "Pull from Flowable Design" reads models from it. */
    DESIGN("Design"),

    /**
     * A running Flowable app — the Expression Playground evaluates against it. Called *Work* in the
     * UI: it pairs with "Flowable Design", it is the product name the user already says, and the
     * codebase says it too (`WorkUrlParser`, "Paste a Work URL"). *Inspect* stays where it belongs —
     * naming the REST API, in the detail form's comment.
     */
    WORK("Work");

    companion object {
        /** The kind named [raw], or null — an unknown name must drop the record, never default. */
        fun byName(raw: String?): ConnectionKind? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}
