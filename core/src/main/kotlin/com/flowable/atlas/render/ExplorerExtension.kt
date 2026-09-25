package com.flowable.atlas.render

/**
 * An optional part of the explorer page, chosen per generation (`--extension` on the CLI, Settings →
 * Generation in the plugin). Its code lives beside the explorer's in `frontend/ext/<id>.{js,css}` and is
 * inlined only when chosen, so a page generated without it is byte-for-byte the page it was before
 * extensions existed — no dormant code, nothing extra to load through a Remote Development connection.
 *
 * The contract on the page side is in `explorer.js` (`EXT`, `EXT_VIEWS`): the extension's script runs
 * before the explorer's and registers itself on `window.ATLAS_EXT`.
 */
enum class ExplorerExtension(val id: String, val label: String, val description: String) {
    ERD(
        "erd",
        "ER diagram designer",
        "A #/erd page to lay out the project's database tables and draw the relations between them",
    ),
    ;

    companion object {
        fun byId(id: String): ExplorerExtension? = entries.firstOrNull { it.id == id.trim().lowercase() }

        /**
         * The extensions named in [ids], in declaration order; blank entries are ignored. An unknown id is an
         * error rather than a skip — a typo in a flag must not silently produce a page without the page asked for.
         */
        fun parse(ids: Iterable<String>): Set<ExplorerExtension> {
            val out = LinkedHashSet<ExplorerExtension>()
            for (raw in ids) {
                if (raw.isBlank()) continue
                out.add(byId(raw) ?: throw IllegalArgumentException(
                    "unknown explorer extension '${raw.trim()}' — known: ${entries.joinToString(", ") { it.id }}",
                ))
            }
            return entries.filterTo(LinkedHashSet()) { it in out }
        }
    }
}
