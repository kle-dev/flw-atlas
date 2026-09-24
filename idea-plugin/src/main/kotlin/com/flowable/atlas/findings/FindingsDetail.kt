package com.flowable.atlas.findings

import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.explorer.ExplorerRoutes
import com.flowable.atlas.findings.FindingsTree.CheckItem
import com.flowable.atlas.findings.FindingsTree.FindingItem
import com.flowable.atlas.findings.FindingsTree.Group
import com.flowable.atlas.graph.CheckCatalog

/**
 * What the detail pane beside the findings tree says about the selected row — data, not Swing, so it is
 * tested without a panel. The tree says *what* was found; this says what the explorer's Checks page says
 * about it: why it matters, what to do, and where to read more — which is what a developer looking at a
 * bare "Gateway without default" row had to go to the browser for.
 */
internal data class FindingsDetail(
    val title: String,
    /** *Defect · broken*, *Advice · noise* — the split every Atlas surface leads with. */
    val kind: String?,
    /** The finding's own sentence: its label and message. Null for a check or a group. */
    val finding: String?,
    /** Project-relative file of the finding, and its line. */
    val file: String?,
    val line: Int?,
    /** The explorer route this row opens — a model's page, or the Checks page filtered to the check. */
    val route: String?,
    val what: String?,
    val why: String?,
    val fix: String?,
    val docsUrl: String?,
    /** How many open findings *Accept…* would take from here; 0 hides it. */
    val acceptable: Int,
    /** A finding already accepted: who, and why. */
    val accepted: String?,
) {
    companion object {
        /** Nothing selected: the pane says what it is for. */
        val EMPTY = FindingsDetail(
            message("findings.detail.empty"), null, null, null, null, null, null, null, null, null, 0, null,
        )

        fun of(item: FindingsTree.Item?, openUnder: Int): FindingsDetail = when (item) {
            null -> EMPTY
            is Group -> EMPTY.copy(title = message("findings.detail.group", item.title, item.count), acceptable = openUnder)
            is CheckItem -> {
                val check = CheckCatalog[item.id]
                FindingsDetail(
                    title = item.title,
                    kind = check?.let(::kindOf),
                    finding = null, file = null, line = null,
                    route = "/checks&f=" + ExplorerRoutes.encodeUriComponent(item.title),
                    what = check?.what?.let { "${item.count} $it" },
                    why = check?.why,
                    fix = check?.fix,
                    docsUrl = check?.let(CheckCatalog::docsUrl),
                    acceptable = openUnder,
                    accepted = null,
                )
            }
            is FindingItem -> {
                val id = item.finding["check"]?.toString().orEmpty()
                val check = CheckCatalog[id]
                val node = item.finding["node"] as? String
                val waived = item.finding["waived"] as? Map<*, *>
                FindingsDetail(
                    title = check?.title ?: id,
                    kind = check?.let(::kindOf),
                    finding = listOf(item.label, item.message).filter { it.isNotBlank() }.joinToString(" — "),
                    file = item.file,
                    line = item.line,
                    route = node?.let(ExplorerRoutes::encodeUriComponent)
                        ?: check?.let { "/checks&f=" + ExplorerRoutes.encodeUriComponent(it.title) },
                    what = null,
                    why = check?.why,
                    fix = check?.fix,
                    docsUrl = check?.let(CheckCatalog::docsUrl),
                    acceptable = if (waived == null) 1 else 0,
                    accepted = waived?.let { w ->
                        listOfNotNull(w["reason"]?.toString(), w["by"]?.toString()?.let { message("findings.detail.by", it) })
                            .joinToString(" · ").ifBlank { null }
                    },
                )
            }
        }

        private fun kindOf(check: CheckCatalog.Check): String =
            message(if (check.kind == CheckCatalog.ADVICE) "findings.kind.advice" else "findings.kind.defect", check.tier)
    }
}
