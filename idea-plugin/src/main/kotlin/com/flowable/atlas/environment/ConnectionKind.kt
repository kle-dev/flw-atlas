package com.flowable.atlas.environment

/**
 * What a connection in an [AtlasEnvironments] catalog talks to. The kinds are deliberately separate
 * records rather than several URL fields on one "environment" row: an environment legitimately has a
 * runtime app and no Design server (a QA stage nobody models against), or a Design server and no
 * running app, and a single record with a field per product would have to encode "absent" as a blank
 * string in every consumer.
 *
 * Two of them are servers Atlas **talks to** and two are addresses it only **opens** — see [linkOnly].
 * The distinction is a property rather than folklore because it decides three separate things
 * (credentials, whether a project points at one, and whether protection applies), and a reader
 * shouldn't have to find all three to learn that Control has no password.
 *
 * Persisted **by name** as a plain `String` on [AtlasEnvironments.ConnectionState] — see the comment
 * there for why the field is not typed as this enum. New kinds are therefore appended freely: the name
 * is the identity, and an older `flowable-atlas-environments.xml` simply has none of them.
 */
enum class ConnectionKind(val display: String, val linkOnly: Boolean) {

    /** A Flowable Design server — "Pull from Flowable Design" reads models from it. */
    DESIGN("Design", linkOnly = false),

    /**
     * A running Flowable app — the Expression Playground evaluates against it. Called *Work* in the
     * UI: it pairs with "Flowable Design", it is the product name the user already says, and the
     * codebase says it too (`WorkUrlParser`, "Paste a Work URL"). *Inspect* stays where it belongs —
     * naming the REST API, in the detail form's comment.
     */
    WORK("Work", linkOnly = false),

    /** Flowable Control — the admin UI for the engines. Atlas only ever hands it to a browser. */
    CONTROL("Control", linkOnly = true),

    /** Flowable Hub. Same story as [CONTROL]: an address per stage, opened, never called. */
    HUB("Hub", linkOnly = true);

    companion object {

        /** The kind named [raw], or null — an unknown name must drop the record, never default. */
        fun byName(raw: String?): ConnectionKind? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }

        /**
         * The kinds Atlas actually connects to: they have credentials, a project points at one of each
         * ([AtlasConnectionSelection]), and *Protected* asks before Atlas uses them.
         *
         * The rest are URLs the user opens in a browser. Nothing is authenticated, nothing is stored in
         * the password safe, and no prompt guards them — opening a page changes nothing, and a
         * confirmation on a link would be theatre.
         */
        val SERVERS: List<ConnectionKind> get() = entries.filterNot { it.linkOnly }
    }
}
