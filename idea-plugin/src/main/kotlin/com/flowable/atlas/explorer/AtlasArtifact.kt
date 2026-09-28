package com.flowable.atlas.explorer

/**
 * The individual artifacts the Atlas generator can emit. The "Generate Atlas Explorer" action
 * produces the subset selected in Settings → Tools → Flowable Atlas → Generation; when only
 * [EXPLORER_HTML] is selected the action asks for a target file, otherwise for a target folder.
 */
enum class AtlasArtifact(val label: String, val suffix: String) {
    /** The self-contained interactive explorer HTML. */
    EXPLORER_HTML("Explorer HTML", ".explorer.html"),

    /**
     * The ER diagram designer: the project's tables and their relations, on a page of its own beside the
     * explorer — small whatever the project's size, so it opens where a large explorer does not.
     */
    ERD_HTML("ER diagram designer (HTML)", ".erd.html"),

    /** Compact LLM-first Markdown summary. */
    SUMMARY_MD("Summary (Markdown)", ".summary.md"),

    /** Exhaustive Markdown overview of the whole model landscape. */
    OVERVIEW_MD("Overview (Markdown)", ".overview.md"),

    /** Traversable graph JSON. */
    GRAPH_JSON("Graph (JSON)", ".graph.json"),

    /** CLAUDE.md context primer for LLM tooling. */
    CLAUDE_MD("CLAUDE.md primer", ".CLAUDE.md"),

    /**
     * Rendered process/case/decision diagrams — one SVG per model, written into a `<name>.diagrams/`
     * folder (the [suffix] names that folder, not a single file). Rendered from each model's diagram
     * interchange layout, so it works even for Design exports that no longer bundle a `.svg`.
     */
    DIAGRAMS_SVG("Diagrams (SVG)", ".diagrams");

    override fun toString(): String = label

    companion object {
        /** A generated page the Atlas editor tab shows: the explorer, or the ER diagram designer. */
        fun isPage(fileName: String): Boolean =
            fileName.endsWith(EXPLORER_HTML.suffix, ignoreCase = true) || fileName.endsWith(ERD_HTML.suffix, ignoreCase = true)

        fun isErdPage(fileName: String): Boolean = fileName.endsWith(ERD_HTML.suffix, ignoreCase = true)
    }
}
