package com.flowable.atlas.graph

import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.model.ModelPaths
import java.io.File

/**
 * An earlier state of the project: its findings, so that a gate can fail on what a change adds rather than
 * on everything the project already had (`--baseline`), and — for the `--changes` report — all of it.
 *
 * The comparison is by [Findings.identity] — the key a waiver uses — so a finding whose message rewords is
 * still the same finding. Nothing is hidden by it: the report, the counts and the checks of the run stay
 * what they are; only the verdict of the gate is narrowed.
 */
object Baseline {

    /**
     * The baseline's findings, the Atlas that wrote them — null when they were analysed in this run — and,
     * when it was asked for, the whole of it: an extraction result or a parsed graph.json, as [GraphDelta.of]
     * takes either.
     */
    class Loaded(val findings: List<Map<String, Any?>>, val writtenBy: String?, val result: Map<String, Any?>?)

    /** [path] cannot be read as a baseline; the message says why, in the user's words. */
    class Unreadable(message: String) : RuntimeException(message)

    /**
     * Read [path]. A `.json` file is a graph.json Atlas wrote — `--all`'s `<name>.graph.json`, or `--json`'s
     * output. Anything else — a checkout of the base, an archive, a model file — is analysed now by [extract],
     * which the caller runs with this run's options and without waivers: a finding the base had waived was
     * still there, and so is not new.
     *
     * [head] is what this run analyses, so an archive's findings can be read as the head names them. [whole]
     * keeps the entire baseline in [Loaded.result]; only a changes report needs it, and on a large project it
     * is a second copy of everything the run holds.
     */
    @Suppress("UNCHECKED_CAST")
    fun load(path: File, head: File? = null, whole: Boolean = false, extract: (File) -> Map<String, Any?>): Loaded {
        if (!path.name.lowercase().endsWith(".json")) {
            val result = extract(path)
            val prefixes = archivePrefixes(path, head)
            val found = (result["findings"] as? List<Map<String, Any?>>).orEmpty()
            val findings = prefixes?.let { (from, to) -> relabel(found, from, to) } ?: found
            val kept = when {
                !whole -> null
                prefixes == null -> result
                else -> result + ("findings" to findings)
            }
            return Loaded(findings, null, kept)
        }
        val root = try {
            MiniJson.parse(path.readText(Charsets.UTF_8))
        } catch (e: MiniJson.JsonException) {
            throw Unreadable("${path.path} is not valid JSON: ${e.message}")
        }
        val notOurs = "${path.path} has no findings — it is not a graph.json Atlas wrote"
        val graph = root as? Map<String, Any?> ?: throw Unreadable(notOurs)
        val findings = graph["findings"] as? List<*> ?: throw Unreadable(notOurs)
        val writtenBy = (graph["_generated"] as? Map<String, Any?>)?.get("atlas") as? String
        return Loaded(findings.filterIsInstance<Map<String, Any?>>(), writtenBy, graph.takeIf { whole })
    }

    /**
     * When the baseline and the head are both archives of different names, the prefix each gives its labels.
     * An archive analysed on its own names every file in it after itself (`base.zip!broken.form`), so the
     * same archive under another name — `git show "$BASE:apps/app.zip" > base.zip`, the obvious way to get the
     * base of a pull request — would make every finding known by its file alone (a file that does not parse)
     * new, and its twin resolved.
     */
    private fun archivePrefixes(path: File, head: File?): Pair<String, String>? {
        if (head == null || !path.isFile || !head.isFile) return null
        if (!ModelPaths.isArchive(path.name) || !ModelPaths.isArchive(head.name) || path.name == head.name) return null
        return "${path.name}!" to "${head.name}!"
    }

    /** [findings] with [from] at the start of a `file` (and of a parse finding's `label`, which is its file) replaced by [to]. */
    private fun relabel(findings: List<Map<String, Any?>>, from: String, to: String): List<Map<String, Any?>> = findings.map { f ->
        val file = f["file"] as? String
        if (file == null || !file.startsWith(from)) f
        else LinkedHashMap(f).apply {
            put("file", to + file.removePrefix(from))
            if (f["label"] == file) put("label", to + file.removePrefix(from))
        }
    }
}
