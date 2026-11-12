package com.flowable.atlas.render

/**
 * Which artifacts a run writes beside the summary and the overview — the only files those two may send a
 * reader to.
 *
 * Both used to name `<name>.overview.md`, `<name>.graph.json`, `<name>.explorer.html` and the diagrams
 * folder unconditionally, while only `--all` writes them all: a `--summary --stdout` handed to an agent
 * — the use the docs recommend — sent it to files that did not exist, which costs it a failed read and
 * its trust in the rest of the page. [ClaudeRenderer.Layout] and [PlaywrightRenderer.Layout] answer the
 * same question for their own files.
 *
 * The defaults describe `--all`, so the goldens (which are `--all` output) pin that run.
 */
data class Siblings(
    val overview: Boolean = true,
    val graph: Boolean = true,
    val explorer: Boolean = true,
    /** `<name>.diagrams/` holds at least one SVG. */
    val diagrams: Boolean = true,
) {
    companion object {
        /** `--all`, or the plugin with every artifact ticked. */
        val ALL = Siblings()

        /** A single-artifact run: nothing is written beside the file. */
        val NONE = Siblings(overview = false, graph = false, explorer = false, diagrams = false)

        /** The IDE action that writes the artifacts ticked in its settings, by the menu path the plugin page names. */
        const val IDE_GENERATE = "*Tools → Flowable Atlas → Generate → Atlas Explorer…*"

        /**
         * How a reader produces the full set, from either generator. The file cannot tell which one wrote
         * it, so it names both — the CLI the way that runs as typed, the IDE by its menu path.
         */
        const val GENERATE = "`java -jar <atlas-cli>.jar <project-dir> --all -o <out-dir>`, or in the IDE " +
            IDE_GENERATE
    }
}
