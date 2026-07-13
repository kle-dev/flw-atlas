package com.flowable.atlas.explorer

/**
 * The individual artifacts the Atlas generator can emit. The "Generate Atlas Explorer" action
 * produces the subset selected in Settings → Tools → Flowable Atlas → Generation; when only
 * [EXPLORER_HTML] is selected the action asks for a target file, otherwise for a target folder.
 */
enum class AtlasArtifact(val label: String, val suffix: String) {
    /** The self-contained interactive explorer HTML. */
    EXPLORER_HTML("Explorer HTML", ".explorer.html"),

    /** Compact LLM-first Markdown summary. */
    SUMMARY_MD("Summary (Markdown)", ".summary.md"),

    /** Exhaustive Markdown overview of the whole model landscape. */
    OVERVIEW_MD("Overview (Markdown)", ".overview.md"),

    /** Traversable graph JSON. */
    GRAPH_JSON("Graph (JSON)", ".graph.json"),

    /** CLAUDE.md context primer for LLM tooling. */
    CLAUDE_MD("CLAUDE.md primer", ".CLAUDE.md");

    override fun toString(): String = label
}
