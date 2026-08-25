package com.flowable.atlas.environment

/**
 * Generates the stable ids of environments and connections.
 *
 * Ids are slugs of the name (`DEV1` → `dev1`, `Design` → `dev1-design`), uniquified with `-2`, `-3`,
 * … — **not** `UUID.randomUUID()`. Three reasons, in the order they mattered:
 *
 * 1. The id lands in workspace-local property keys, where `flowable.atlas.designConnection=dev1-design`
 *    can be read by whoever is debugging why a project pulls from the wrong server. A UUID there is a
 *    dead end.
 * 2. `flowable-atlas-environments.xml` stays diffable and mergeable.
 * 3. A random id forces an injected id-supplier seam into every test that creates a connection.
 *
 * An id is generated once, at creation, and **never changes on rename** — a rename that re-slugged
 * would orphan every project's pointer at it.
 *
 * The alphabet also guarantees an id can never contain [SEPARATOR], which is what lets
 * [com.flowable.atlas.project.AtlasScopedKeys] append one to a property key without ambiguity.
 */
internal object AtlasConnectionIds {

    /** The character scoped property keys use to append a connection id; never produced by [slug]. */
    const val SEPARATOR = '@'

    private const val FALLBACK = "connection"

    /** [name] as `[a-z0-9-]+`; blank or punctuation-only names fall back to [FALLBACK]. */
    fun slug(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { FALLBACK }

    /**
     * A slug of [name] that is not in [taken]: the slug itself, else `-2`, `-3`, … The caller passes
     * the ids it has already handed out, so a batch (an import creating four connections) stays
     * collision-free without consulting the store between each one.
     */
    fun newId(name: String, taken: Set<String>): String {
        val base = slug(name)
        if (base !in taken) return base
        var suffix = 2
        while ("$base-$suffix" in taken) suffix++
        return "$base-$suffix"
    }

    /** `dev1` + `Design` → `dev1-design`, so a connection id reads as "which one, in which environment". */
    fun newConnectionId(environmentName: String, connectionName: String, taken: Set<String>): String =
        newId("${slug(environmentName)}-${slug(connectionName)}", taken)
}
