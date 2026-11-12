package com.flowable.atlas.render

import com.flowable.atlas.AtlasBuildInfo
import com.flowable.atlas.graph.CheckCatalog
import com.flowable.atlas.graph.GraphDelta

/**
 * The `changes.md` artifact (`--changes`, and `--all` with `--baseline`): what a change did to the project,
 * as [GraphDelta] found it — short enough for a pull-request comment, a CI job's step summary or an agent
 * reviewing the change, where the diff itself shows a binary archive.
 *
 * Models first, one line each, with the ids of the elements that came, went or changed; then the references;
 * then the findings the change brought and the ones it fixed. Every list is in a fixed order — an element list
 * in the model's own — and capped, so the same change reads the same in every run, and a change to hundreds of
 * models still fits in a comment.
 */
object ChangesRenderer {

    /** Element ids named per kind on one model's line. */
    private const val ELEMENTS_LISTED = 20
    /** Models listed per section. */
    private const val MODELS_LISTED = 50
    /** References listed per section. */
    private const val EDGES_LISTED = 40
    /** Findings listed per section — the gate's own limit on what a red run names. */
    private const val FINDINGS_LISTED = 50

    private val ORDER_INDEX: Map<String, Int> = CheckCatalog.ORDER.withIndex().associate { (i, c) -> c to i }
    private val LINE_BREAK = Regex("\\s*[\\r\\n]+\\s*")

    /**
     * [against] names the baseline as the user gave it. [baselineAtlas] is the Atlas that wrote a graph.json
     * baseline, when it was one: a check that changed between two versions reads as findings new and resolved,
     * and a pull-request comment is read by someone who never sees the warning on stderr.
     */
    fun render(delta: GraphDelta, name: String, against: String, baselineAtlas: String? = null): String {
        val l = ArrayList<String>()
        l.add("# What changed — `$name`")
        l.add("")
        if (baselineAtlas != null && baselineAtlas != AtlasBuildInfo.VERSION) {
            l.add("_The baseline was written by Atlas $baselineAtlas, and this is ${AtlasBuildInfo.VERSION}: a check that " +
                "changed between the two shows as findings new and resolved. Compare with the base's sources to rule it out._")
            l.add("")
        }
        if (delta.isEmpty) {
            l.add("No model, reference or finding changed since `$against`.")
            return l.joinToString("\n")
        }
        l.add("_Compared with `$against`: models ${delta.modelsAdded.size} added, ${delta.modelsRemoved.size} removed, " +
            "${delta.modelsChanged.size} changed · references ${delta.edgesAdded.size} added, ${delta.edgesRemoved.size} removed · " +
            "findings ${delta.findingsNew.size} new, ${delta.findingsResolved.size} resolved._")
        l.add("")

        if (delta.modelsAdded.isNotEmpty() || delta.modelsRemoved.isNotEmpty() || delta.modelsChanged.isNotEmpty()) {
            l.add("## Models")
            l.add("")
            section(l, "Added", delta.modelsAdded, MODELS_LISTED) { m -> model(m) + (m.file?.let { " — `$it`" } ?: "") }
            section(l, "Removed", delta.modelsRemoved, MODELS_LISTED) { m -> model(m) + (m.file?.let { " — `$it`" } ?: "") }
            section(l, "Changed", delta.modelsChanged, MODELS_LISTED) { c ->
                val what = Fmt.fields(
                    "added" to ids(c.added),
                    "removed" to ids(c.removed),
                    "changed" to Fmt.cap(c.changed.map { e -> "`${e.id}` (${e.fields.joinToString(", ")})" }, ELEMENTS_LISTED),
                    "properties" to ids(c.properties),
                    "changed in another file" to ids(c.elsewhere),
                )
                "${model(c.model)} — $what"
            }
        }
        if (delta.edgesAdded.isNotEmpty() || delta.edgesRemoved.isNotEmpty()) {
            l.add("## References")
            l.add("")
            section(l, "Added", delta.edgesAdded, EDGES_LISTED, ::edge)
            section(l, "Removed", delta.edgesRemoved, EDGES_LISTED, ::edge)
        }
        if (delta.findingsNew.isNotEmpty() || delta.findingsResolved.isNotEmpty()) {
            l.add("## Findings")
            l.add("")
            section(l, "New", worstFirst(delta.findingsNew), FINDINGS_LISTED, ::finding)
            section(l, "Resolved", worstFirst(delta.findingsResolved), FINDINGS_LISTED, ::finding)
        }
        while (l.last().isEmpty()) l.removeAt(l.size - 1)
        // No newline at the end, like the other Markdown artifacts: `--stdout` adds one.
        return l.joinToString("\n")
    }

    private fun <T> section(l: MutableList<String>, title: String, items: List<T>, cap: Int, line: (T) -> String) {
        if (items.isEmpty()) return
        l.add("**$title (${items.size})**")
        for (item in items.take(cap)) l.add("- " + line(item).replace(LINE_BREAK, " "))
        if (items.size > cap) l.add("- … (+${items.size - cap} more)")
        l.add("")
    }

    /** The id, and the name when it says more than the key the id already holds. */
    private fun model(m: GraphDelta.Model): String {
        val name = m.name?.takeIf { it.isNotBlank() && it != m.id.substringAfter(':') }
        return "`${m.id}`" + (name?.let { " $it" } ?: "")
    }

    private fun ids(items: List<String>): String = Fmt.cap(items.map { "`$it`" }, ELEMENTS_LISTED)

    private fun edge(e: GraphDelta.Edge): String = "`${e.s}` → `${e.t}` (${e.rel})"

    /** Worst first, then in the checks page's order, then by place: the order a reader fixes them in. */
    private fun worstFirst(findings: List<Map<String, Any?>>): List<Map<String, Any?>> = findings.sortedWith(
        compareBy<Map<String, Any?>>({ if (it["severity"] == "error") 0 else 1 }, { ORDER_INDEX[it["check"]] ?: Int.MAX_VALUE },
            { it["node"]?.toString() ?: it["file"]?.toString() ?: "" }, { it["element"]?.toString() ?: "" },
            { it["subject"]?.toString() ?: "" }),
    )

    /** The overview's finding line, led by the check id a waiver or `--fail-on` names it by. */
    private fun finding(f: Map<String, Any?>): String {
        val mark = if (f["severity"] == "error") "⚠" else "·"
        // A parse finding's label is its file: the same path twice says nothing more.
        val file = f["file"]?.toString()?.takeIf { it != f["label"]?.toString() }
        val where = Fmt.fields(
            "in" to Fmt.codeList(f["label"]),
            "at" to Fmt.codeList(f["element"]),
            "file" to Fmt.codeList(file),
        )
        val waived = (f["waived"] as? Map<*, *>)?.let { w ->
            " — waived: " + (w["reason"]?.toString()?.takeIf { it.isNotBlank() } ?: "no reason given")
        } ?: ""
        return "$mark `${f["check"]}` ${f["message"] ?: ""}" + (if (where.isEmpty()) "" else " — $where") + waived
    }
}
