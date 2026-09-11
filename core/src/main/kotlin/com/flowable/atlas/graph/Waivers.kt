package com.flowable.atlas.graph

import com.flowable.atlas.model.MiniJson
import java.io.File
import java.time.LocalDate

/**
 * The findings a team has decided to accept, and the notes they left about them.
 *
 * A waiver is a statement **about an element** — "this task's missing error path is deliberate" — not a
 * pointer at one row of one report. That is why it is keyed by check + node + element + subject and not
 * by the message: the message is generated prose that rewords as a model changes, so a key built on it
 * would lapse for reasons that have nothing to do with the decision it records.
 *
 * The file is meant to be committed and reviewed. Everything here follows from that: it is JSON because
 * the browser writes it too, it is sorted because a diff nobody can read is a diff nobody reviews, and a
 * waiver that no longer matches anything is reported rather than dropped — a suppression that silently
 * stops applying is the one failure mode this must not have.
 */
object Waivers {

    const val FILE_NAME = "waivers.json"

    /** The schema version written into new files. Bumped only when a reader would otherwise misread one. */
    const val SCHEMA_VERSION = 1

    /**
     * One accepted finding. [check] and [node] are the identity; [element] and [subject] narrow it, and
     * when absent the rule covers every finding of that check on that node. A parse finding has no
     * node, so [node] is the path of the file that would not read. There is no separate `file` key:
     * a node id already names its file, and a second identity can only disagree with the first.
     */
    data class Waiver(
        val check: String,
        val node: String,
        val element: String? = null,
        val subject: String? = null,
        val reason: String = "",
        val by: String? = null,
        val at: String? = null,
        val until: String? = null,
    ) {
        /** Sort key — the file is ordered by it so re-saving an unchanged set is byte-identical. */
        val sortKey: String get() = listOf(check, node, element ?: "", subject ?: "").joinToString(" ")

        fun expiredOn(today: LocalDate): Boolean {
            val u = until?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val date = runCatching { LocalDate.parse(u) }.getOrNull() ?: return false
            return today.isAfter(date)
        }

        fun covers(finding: Map<String, Any?>): Boolean {
            if (finding["check"] != check) return false
            val fNode = finding["node"] as? String
            // A parse failure has no node — it is keyed by the file it could not read.
            if (fNode != null) { if (fNode != node) return false }
            else if (finding["file"] as? String != node) return false
            if (element != null && finding["element"] as? String != element) return false
            if (subject != null && finding["subject"] as? String != subject) return false
            return true
        }
    }

    /**
     * A remark that changes nothing. Notes exist because a review produces two kinds of decision —
     * "this is fine" and "this needs a look in Q3" — and only writing down the first kind loses the
     * reasoning that made the review worth doing.
     */
    data class Note(
        val node: String,
        val text: String,
        val check: String? = null,
        val element: String? = null,
        val subject: String? = null,
        val importance: String = "normal",
        val by: String? = null,
        val at: String? = null,
    ) {
        val sortKey: String get() = listOf(node, check ?: "", element ?: "", subject ?: "", text).joinToString(" ")
    }

    /**
     * A parsed file. [problems] carries what was wrong with it in the user's words; an unreadable or
     * half-broken waiver file must never fail the run — the analysis is still correct without it, and a
     * tool that refuses to report because its suppression list has a typo is a tool people stop running.
     */
    class Set(
        val waivers: List<Waiver> = emptyList(),
        val notes: List<Note> = emptyList(),
        val problems: List<String> = emptyList(),
        val createdWith: String? = null,
    ) {
        val isEmpty: Boolean get() = waivers.isEmpty() && notes.isEmpty() && problems.isEmpty()

        /** Rules a reviewer cannot review, because they do not say why. */
        fun unexplained(): List<String> =
            waivers.filter { it.reason.isBlank() }.map { "no reason given: ${it.check} on ${it.node}" }
    }

    /**
     * One run's matching of a [Set] against findings. The counts live here rather than on the set
     * because a set is data that outlives a run — the plugin keeps one and analyses again — and a
     * counter on it would add every run to the last.
     */
    class Matching(val set: Set, val today: LocalDate = LocalDate.now()) {
        // By position, not by key: two rules that say the same thing share a sort key and are still
        // two rules, each with its own count.
        private val hits = IntArray(set.waivers.size)

        /**
         * The waiver covering [finding], or null. Expired rules deliberately do not match: the author
         * asked for the check to come back on that date, and honouring that is the whole point of
         * writing one.
         *
         * Every covering rule is counted, not only the one returned: two rules that both cover a
         * finding are both true, and reporting the second as "matched nothing" would tell a team to
         * delete a rule that is doing exactly what it says.
         */
        fun match(finding: Map<String, Any?>): Waiver? {
            var first: Waiver? = null
            for ((i, w) in set.waivers.withIndex()) {
                if (w.expiredOn(today) || !w.covers(finding)) continue
                hits[i]++
                if (first == null) first = w
            }
            return first
        }

        /** How many findings the rule at [index] in [Set.waivers] covered in this run. */
        fun matchCount(index: Int): Int = hits[index]

        /**
         * Rules that matched nothing in this run, in the user's words. A waiver goes stale when the model
         * it pointed at was renamed or fixed, and saying so is what keeps the file from silently rotting
         * into a list of claims about code that no longer exists.
         */
        fun stale(): List<String> = buildList {
            for ((i, w) in set.waivers.withIndex()) {
                when {
                    w.expiredOn(today) -> add("expired on ${w.until}: ${w.check} on ${w.node}")
                    hits[i] == 0 -> add("matched nothing: ${w.check} on ${w.node}")
                }
            }
        }
    }

    val EMPTY = Set()

    // ---- reading ---------------------------------------------------------------------------------

    /**
     * The page's version of the file, plus what the file gained while the page was open.
     *
     * The explorer rebuilds `waivers.json` from the rules baked in at generation time and the decisions
     * taken since; saving that wholesale deleted every rule a colleague, the CLI or a text editor had
     * added to the file in the meantime. [seen] is what the page started from (rule ids as [Waiver.sortKey]
     * / [Note.sortKey]); a rule on disk that the page never saw is kept, one it saw and dropped is gone.
     */
    fun merge(page: Set, disk: Set, seen: Collection<String>): Set {
        val pageW = page.waivers.map { it.sortKey }.toHashSet()
        val pageN = page.notes.map { it.sortKey }.toHashSet()
        val keptW = disk.waivers.filter { it.sortKey !in seen && it.sortKey !in pageW }
        val keptN = disk.notes.filter { it.sortKey !in seen && it.sortKey !in pageN }
        return Set(page.waivers + keptW, page.notes + keptN, createdWith = page.createdWith ?: disk.createdWith)
    }

    fun load(file: File): Set =
        if (!file.isFile) EMPTY
        else runCatching { parse(file.readText()) }
            .getOrElse { Set(problems = listOf("could not read ${file.name}: ${it.message}")) }

    @Suppress("UNCHECKED_CAST")
    fun parse(text: String): Set {
        val root = MiniJson.parseOrNull(text) as? Map<String, Any?>
            ?: return Set(problems = listOf("$FILE_NAME is not a JSON object"))
        val problems = ArrayList<String>()

        val version = (root["version"] as? Number)?.toInt() ?: SCHEMA_VERSION
        if (version > SCHEMA_VERSION) {
            problems += "$FILE_NAME says version $version, this Atlas understands $SCHEMA_VERSION — " +
                "rules it does not recognise are ignored"
        }

        fun str(m: Map<String, Any?>, k: String): String? =
            (m[k] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        val waivers = ArrayList<Waiver>()
        for ((i, any) in (root["waivers"] as? List<Any?> ?: emptyList()).withIndex()) {
            val m = any as? Map<String, Any?> ?: run { problems += "waivers[$i] is not an object"; continue }
            val check = str(m, "check") ?: run { problems += "waivers[$i] has no check"; continue }
            val node = str(m, "node") ?: run { problems += "waivers[$i] has no node"; continue }
            waivers += Waiver(
                check = check, node = node,
                element = str(m, "element"), subject = str(m, "subject"),
                reason = str(m, "reason") ?: "",
                by = str(m, "by"), at = str(m, "at"), until = str(m, "until"),
            )
        }

        val notes = ArrayList<Note>()
        for ((i, any) in (root["notes"] as? List<Any?> ?: emptyList()).withIndex()) {
            val m = any as? Map<String, Any?> ?: run { problems += "notes[$i] is not an object"; continue }
            val node = str(m, "node") ?: run { problems += "notes[$i] has no node"; continue }
            val text2 = str(m, "text") ?: run { problems += "notes[$i] has no text"; continue }
            notes += Note(
                node = node, text = text2,
                check = str(m, "check"), element = str(m, "element"), subject = str(m, "subject"),
                importance = str(m, "importance") ?: "normal",
                by = str(m, "by"), at = str(m, "at"),
            )
        }

        return Set(waivers.sortedBy { it.sortKey }, notes.sortedBy { it.sortKey }, problems, str(root, "createdWith"))
    }

    // ---- writing ---------------------------------------------------------------------------------

    /**
     * The file, byte-for-byte reproducible. Sorted and indented exactly the way the explorer's exporter
     * must write it: two writers exist for this format — here and in the browser — and the moment they
     * disagree on key order the file churns in every diff.
     */
    fun serialize(set: Set, version: String, today: LocalDate = LocalDate.now()): String {
        fun waiverMap(w: Waiver) = linkedMapOf<String, Any?>("check" to w.check, "node" to w.node).apply {
            w.element?.let { put("element", it) }
            w.subject?.let { put("subject", it) }
            put("reason", w.reason)
            w.by?.let { put("by", it) }
            w.at?.let { put("at", it) }
            w.until?.let { put("until", it) }
        }
        fun noteMap(n: Note) = linkedMapOf<String, Any?>("node" to n.node).apply {
            n.check?.let { put("check", it) }
            n.element?.let { put("element", it) }
            n.subject?.let { put("subject", it) }
            put("text", n.text)
            put("importance", n.importance)
            n.by?.let { put("by", it) }
            n.at?.let { put("at", it) }
        }
        val out = linkedMapOf<String, Any?>(
            "version" to SCHEMA_VERSION,
            // A date, not an instant: re-saving an unchanged set must not produce a diff.
            "createdWith" to (set.createdWith ?: version),
            "updatedWith" to version,
            "waivers" to set.waivers.sortedBy { it.sortKey }.map { waiverMap(it) },
            "notes" to set.notes.sortedBy { it.sortKey }.map { noteMap(it) },
        )
        return MiniJson.stringify(out, 2) + "\n"
    }

    /**
     * The `.gitignore` Atlas drops next to the generated artifacts.
     *
     * The output folder is regenerated wholesale and may carry client data, so it is the last place that
     * should be committed — but the waivers in it are decisions a team made, and they belong in review.
     * Ignoring everything except that one file lets the folder be committed without ever carrying an
     * analysis into a repository.
     */
    /**
     * Drop [OUTPUT_GITIGNORE] into [dir] unless the folder already has one. Written once, never
     * rewritten: a project that tuned its version keeps it. Every writer of an output folder — the CLI's
     * `--all` and the plugin's generate-all — calls this, so the folder is safe to commit whichever
     * tool produced it.
     */
    fun ensureOutputGitignore(dir: File) {
        val f = File(dir, ".gitignore")
        if (!f.exists()) f.writeText(OUTPUT_GITIGNORE, Charsets.UTF_8)
    }

    /**
     * Who is accepting findings, for the `by` of a rule written from the explorer: the project's git
     * identity when there is one, else the OS user. Never throws and never blocks for long — a name is
     * a convenience, not a gate — and returns null rather than guessing when neither answers.
     */
    fun defaultAuthor(root: File): String? {
        val git = runCatching {
            val p = ProcessBuilder("git", "-C", root.absolutePath, "config", "user.name")
                .redirectErrorStream(true).start()
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return@runCatching null }
            if (p.exitValue() != 0) return@runCatching null
            p.inputStream.bufferedReader().readText().trim().ifEmpty { null }
        }.getOrNull()
        return git ?: System.getProperty("user.name")?.trim()?.takeIf { it.isNotEmpty() }
    }

    val OUTPUT_GITIGNORE = """
        # Written by Flowable Atlas. The analysis here is regenerated and may contain client data;
        # waivers.json is the one file that is yours — decisions about findings, meant to be reviewed.
        *
        !.gitignore
        !$FILE_NAME
    """.trimIndent() + "\n"
}
