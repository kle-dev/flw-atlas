package com.flowable.atlas.cli

import com.flowable.atlas.graph.CheckCatalog
import java.io.File

/** The one `--annotate` value: GitHub Actions' workflow commands. */
internal const val ANNOTATE_GITHUB = "github"

/**
 * How many findings a red run names in its log. Enough for any change worth gating; a backlog of hundreds
 * scrolls the reason for the red build out of sight, and the artifacts hold every one of them.
 */
internal const val GATE_LISTED = 50

/** Worst first, then in the catalog's reading order, then by place: the order a reader fixes them in. */
private val ORDER_INDEX: Map<String, Int> = CheckCatalog.ORDER.withIndex().associate { (i, c) -> c to i }

private fun gateOrder(findings: List<Map<String, Any?>>): List<Map<String, Any?>> = findings.sortedWith(
    compareBy<Map<String, Any?>>({ if (it["severity"] == "error") 0 else 1 }, { ORDER_INDEX[it["check"]] ?: Int.MAX_VALUE },
        { it["file"]?.toString() ?: "" }, { it["label"]?.toString() ?: "" }),
)

/**
 * Where a finding is: `file:line`, a line of the file for every check. A script problem whose file line
 * could not be found — an action's script lives in a JSON string — carries `scriptLine` instead, which
 * counts from the start of the script, so it is said as the script's line and never as `file:N`. A finding
 * with no file — a variable, an expression several models hold — is where its node id says.
 */
private fun where(f: Map<String, Any?>): String {
    val file = f["file"] as? String ?: return f["node"]?.toString() ?: "-"
    f["line"]?.let { return "$file:${lineOf(it)}" }
    return f["scriptLine"]?.let { "$file (script line ${lineOf(it)})" } ?: file
}

/**
 * What in that place the finding is about: the model's label — only beside a file, which does not say which
 * model it holds; a node id already does — and the element.
 */
private fun about(f: Map<String, Any?>): String {
    val file = f["file"] as? String
    // A finding about a file as such (a changelog, a parse failure) is labelled with the file's own name.
    val named = f["label"]?.toString()?.takeIf { file != null && it != file && !file.endsWith("/$it") && !file.endsWith("!$it") }
    val element = (f["element"] as? String)?.let { "@$it" }
    return listOfNotNull(named, element).joinToString(" ")
}

private fun message(f: Map<String, Any?>): String = f["message"]?.toString().orEmpty()

/**
 * The rows a failed gate prints on stderr, one per finding, the first [cap] of them, then a line saying how
 * many more there are and where [more] they all are:
 * `  error   scriptIssues  processes/DEMO-p.bpmn:12  DEMO p @DEMO-task — '(' is never closed`.
 */
internal fun failingLines(failing: List<Map<String, Any?>>, more: String, cap: Int = GATE_LISTED): List<String> {
    val sorted = gateOrder(failing)
    val shown = sorted.take(cap)
    val checkWidth = shown.maxOfOrNull { it["check"].toString().length } ?: 0
    val lineBreak = Regex("\\s*[\\r\\n]+\\s*")
    val rows = shown.map { f ->
        val about = about(f).let { if (it.isEmpty()) "" else "  $it" }
        // Messages quote model text, and an expression's node id is the expression, which can span lines: a
        // line break in either would start a row of its own.
        "  ${f["severity"].toString().padEnd(7)} ${f["check"].toString().padEnd(checkWidth)}  ${where(f)}$about — ${message(f)}"
            .replace(lineBreak, " ")
    }
    return if (sorted.size > cap) rows + "  … +${sorted.size - cap} more — $more" else rows
}

/**
 * The findings as GitHub Actions workflow commands, which GitHub shows as annotations on the run and on the
 * pull request's files. A file is given relative to [cwd] — the checkout, where the job runs — because the
 * project Atlas analysed need not be the repository root. No `line` for a file inside an archive (GitHub can
 * only point at the archive), and a script problem's `scriptLine` is the script's, not the file's, so it
 * is never given as one.
 */
internal fun githubAnnotations(
    failing: List<Map<String, Any?>>, projectDir: File, cwd: File = File("."), cap: Int = GATE_LISTED,
): List<String> = gateOrder(failing).take(cap).map { f ->
    val level = if (f["severity"] == "error") "error" else "warning"
    val props = ArrayList<String>()
    val file = f["file"] as? String
    if (file != null) {
        props += "file=" + ghProperty(repoPath(File(projectDir, file.substringBefore('!')), cwd))
        val line = f["line"]
        if (line != null && '!' !in file) props += "line=" + ghProperty(lineOf(line))
    }
    props += "title=" + ghProperty("Atlas ${f["check"]}")
    // Beside a file the annotation sits on it; without one it shows on the run, and has to say what it is about.
    val lead = if (file == null) where(f) else about(f)
    val text = if (lead.isEmpty()) message(f) else "$lead — ${message(f)}"
    "::$level ${props.joinToString(",")}::${ghData(text)}"
}

/** A number as JSON gave it (a Double) or as a parser did (an Int), written as the integer it is. */
private fun lineOf(v: Any): String = (v as? Number)?.toLong()?.toString() ?: v.toString()

private fun repoPath(file: File, cwd: File): String {
    val abs = file.absoluteFile.normalize().toPath()
    val base = cwd.absoluteFile.normalize().toPath()
    // Another drive on Windows has no relative path; the absolute one is still a path GitHub can show.
    val rel = runCatching { base.relativize(abs) }.getOrNull() ?: abs
    return rel.toString().replace(File.separatorChar, '/')
}

/** Escaping as GitHub's toolkit does it: data loses its line breaks and percent signs, a property also `:` and `,`. */
private fun ghData(s: String): String = s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")

private fun ghProperty(s: String): String = ghData(s).replace(":", "%3A").replace(",", "%2C")
