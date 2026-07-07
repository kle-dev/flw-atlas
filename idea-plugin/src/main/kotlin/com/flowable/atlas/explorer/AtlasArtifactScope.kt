package com.flowable.atlas.explorer

/**
 * What the "Generate Atlas Explorer" action produces. The Atlas generator can emit just the
 * interactive HTML explorer, or the full set of artifacts (`--all`): summary + overview Markdown,
 * the traversable graph JSON, the explorer HTML and a CLAUDE.md primer.
 */
enum class AtlasArtifactScope(val label: String) {
    /** Only the self-contained interactive explorer HTML (written to a file you pick). */
    EXPLORER_ONLY("Explorer HTML only"),

    /** All five artifacts (summary.md, overview.md, graph.json, explorer.html, CLAUDE.md) into a folder. */
    ALL_ARTIFACTS("All artifacts (summary, overview, graph, explorer, CLAUDE.md)");

    override fun toString(): String = label
}
