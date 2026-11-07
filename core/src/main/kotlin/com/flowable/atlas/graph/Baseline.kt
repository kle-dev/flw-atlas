package com.flowable.atlas.graph

import com.flowable.atlas.model.MiniJson
import java.io.File

/**
 * The findings of an earlier state of the project, so that a gate can fail on what a change adds rather than
 * on everything the project already had (`--baseline`).
 *
 * The comparison is by [Findings.identity] — the key a waiver uses — so a finding whose message rewords is
 * still the same finding. Nothing is hidden by it: the report, the counts and the checks of the run stay
 * what they are; only the verdict of the gate is narrowed.
 */
object Baseline {

    /** The baseline's findings, and the Atlas that wrote them — null when they were analysed in this run. */
    class Loaded(val findings: List<Map<String, Any?>>, val writtenBy: String?)

    /** [path] cannot be read as a baseline; the message says why, in the user's words. */
    class Unreadable(message: String) : RuntimeException(message)

    /**
     * Read [path]. A `.json` file is a graph.json Atlas wrote — `--all`'s `<name>.graph.json`, or `--json`'s
     * output. Anything else — a checkout of the base, an archive, a model file — is analysed now by [extract],
     * which the caller runs with this run's options and without waivers: a finding the base had waived was
     * still there, and so is not new.
     */
    @Suppress("UNCHECKED_CAST")
    fun load(path: File, extract: (File) -> Map<String, Any?>): Loaded {
        if (!path.name.lowercase().endsWith(".json")) {
            return Loaded((extract(path)["findings"] as? List<Map<String, Any?>>).orEmpty(), null)
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
        return Loaded(findings.filterIsInstance<Map<String, Any?>>(), writtenBy)
    }
}
