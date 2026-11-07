package com.flowable.atlas.render

import com.flowable.atlas.graph.CheckCatalog
import com.flowable.atlas.graph.Findings

/**
 * The findings as a JUnit XML report (`--junit <file>`) — the format every CI test view reads: GitLab's
 * merge-request widget, Jenkins, Azure DevOps' test tab, GitHub's test-reporter actions. Without it a
 * pipeline knows an exit code and has an HTML file to download.
 *
 * One `<testsuite>` per check, in the catalog's reading order, and one `<testcase>` per finding. A check
 * with nothing to report is one passing testcase, so the view shows that it ran. A testcase is named by the
 * finding's identity — node, element, subject; never the message, which rewords — because GitLab tells new,
 * fixed and still-failing tests apart by name across runs, and that only works on a name that stays put.
 *
 * Which finding is a `<failure>` is decided by [CheckCatalog.gates], the predicate the exit code uses, so
 * the report and the build cannot disagree. Everything else is `<skipped>` with the reason — waived, not
 * gated, in the baseline — rather than passing: it is there, and a reader should see why it is not red.
 *
 * SARIF is deliberately not written: nearly every finding with a file sits inside a `.zip`/`.bar`, many
 * have no file at all, and code scanning needs a repository file and line for each result.
 */
object JUnitRenderer {

    private const val OTHER = "other"

    /**
     * [gate] is the `--fail-on` list (the caller passes `defects` when there is none: a report needs a
     * red/green split even when the build is not gated). [baselined] is true for a finding a `--baseline`
     * already had. [staleGate] is `--fail-on-stale-waivers`: stale waivers are a suite of their own, red only
     * when the run fails on them.
     */
    @Suppress("UNCHECKED_CAST")
    fun render(
        result: Map<String, Any?>,
        gate: List<String>,
        baselined: (Map<String, Any?>) -> Boolean = { false },
        staleGate: Boolean = false,
    ): String {
        val findings = (result["findings"] as? List<Map<String, Any?>>).orEmpty()
        val byCheck = findings.groupBy { f -> f["check"]?.toString()?.takeIf { CheckCatalog[it] != null } ?: OTHER }
        val suites = ArrayList<Suite>()
        for (check in CheckCatalog.CHECKS) suites += findingSuite(check.id, byCheck[check.id].orEmpty(), gate, baselined)
        byCheck[OTHER]?.let { suites += findingSuite(OTHER, it, gate, baselined) }
        // Only where a waiver file was read: a suite about a file the project does not have would be noise.
        (result["waivers"] as? Map<String, Any?>)?.let { w ->
            val stale = (w["stale"] as? List<*>).orEmpty().map { it.toString() }
            suites += Suite("waivers", if (stale.isEmpty()) listOf(Case("no stale waivers")) else stale.map {
                if (staleGate) Case(it, failure = Failure("stale waiver", it, "$it\nThe waiver file has a rule that " +
                    "no longer applies: remove it, or fix what it points at."))
                else Case(it, skipped = "not gated (--fail-on-stale-waivers is off)")
            })
        }

        val out = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<testsuites name=\"Flowable Atlas\"").append(counts(suites.flatMap { it.cases })).append(">\n")
        for (s in suites) {
            out.append("  <testsuite name=\"atlas.").append(attr(s.check)).append('"').append(counts(s.cases)).append(">\n")
            for (c in s.cases) {
                out.append("    <testcase classname=\"atlas.").append(attr(s.check)).append("\" name=\"").append(attr(c.name)).append('"')
                c.file?.let { out.append(" file=\"").append(attr(it)).append('"') }
                c.line?.let { out.append(" line=\"").append(attr(it)).append('"') }
                when {
                    c.failure != null -> out.append(">\n      <failure type=\"").append(attr(c.failure.type))
                        .append("\" message=\"").append(attr(c.failure.message)).append("\">")
                        .append(text(c.failure.body)).append("</failure>\n    </testcase>\n")
                    c.skipped != null -> out.append(">\n      <skipped message=\"").append(attr(c.skipped)).append("\"/>\n    </testcase>\n")
                    else -> out.append("/>\n")
                }
            }
            out.append("  </testsuite>\n")
        }
        return out.append("</testsuites>\n").toString()
    }

    private class Failure(val type: String, val message: String, val body: String)
    private class Case(
        val name: String, val file: String? = null, val line: String? = null,
        val failure: Failure? = null, val skipped: String? = null,
    )
    private class Suite(val check: String, val cases: List<Case>)

    private fun findingSuite(
        check: String, findings: List<Map<String, Any?>>, gate: List<String>, baselined: (Map<String, Any?>) -> Boolean,
    ): Suite {
        if (findings.isEmpty()) return Suite(check, listOf(Case("no findings")))
        val seen = HashMap<String, Int>()
        val cases = findings.map { f ->
            val base = name(f)
            // One check can fire twice with one identity; the name must still be unique in the suite, and the
            // ordinal follows the report's stable order.
            val n = seen.merge(base, 1, Int::plus)!!
            val name = if (n > 1) "$base ($n)" else base
            val message = f["message"]?.toString().orEmpty()
            val waived = f["waived"] as? Map<*, *>
            val file = f["file"]?.toString()
            // An archive entry's line is in a file the viewer cannot open, so it would point into the wrong
            // place; a script problem's `scriptLine` counts from the script and is never the case's line.
            val line = f["line"]?.takeIf { file != null && '!' !in file }?.let(::number)
            when {
                waived != null -> Case(name, file, line, skipped = "waived: " + waivedReason(waived))
                !CheckCatalog.gates(gate, f) -> Case(name, file, line, skipped = "not gated by ${gate.joinToString(",")}: $message")
                baselined(f) -> Case(name, file, line, skipped = "in the baseline: $message")
                else -> Case(name, file, line, failure = Failure(f["severity"]?.toString() ?: "error", message, body(f)))
            }
        }
        return Suite(check, cases)
    }

    /** The finding's identity as a reader names it: the place, then the element, then the subject. */
    private fun name(f: Map<String, Any?>): String =
        listOfNotNull((f["node"] ?: f["file"])?.toString(), f["element"]?.toString(), f["subject"]?.toString())
            .joinToString(" › ").ifEmpty { Findings.identity(f) }

    private fun waivedReason(w: Map<*, *>): String = buildString {
        append(w["reason"]?.toString().orEmpty())
        w["by"]?.let { append(" — by ").append(it) }
        w["until"]?.let { append(", until ").append(it) }
    }

    /** What a reader of a CI failure needs without opening anything else: what, where, why it matters, what to do. */
    private fun body(f: Map<String, Any?>): String {
        val check = CheckCatalog[f["check"]?.toString().orEmpty()]
        val lines = ArrayList<String>()
        if (check != null) lines += "${check.title} · ${check.kind} · ${f["severity"]}"
        f["label"]?.let { lines += it.toString() }
        f["file"]?.let { file ->
            val line = f["line"]?.let(::number)
            val scriptLine = f["scriptLine"]?.let(::number)
            lines += "file: $file" + when {
                line != null -> ":$line"
                scriptLine != null -> " (script line $scriptLine)"
                else -> ""
            }
        }
        f["element"]?.let { lines += "element: $it" }
        lines += f["message"]?.toString().orEmpty()
        if (check != null) {
            lines += "why: ${check.why}"
            lines += "fix: ${check.fix}"
            lines += "docs: ${CheckCatalog.docsUrl(check)}"
        }
        return lines.joinToString("\n")
    }

    /** A number as JSON gave it (a Double) or as a parser did (an Int), written as the integer it is. */
    private fun number(v: Any): String = (v as? Number)?.toLong()?.toString() ?: v.toString()

    private fun counts(cases: List<Case>): String =
        " tests=\"${cases.size}\" failures=\"${cases.count { it.failure != null }}\" errors=\"0\" skipped=\"${cases.count { it.skipped != null }}\""

    /** Attribute text: markup escaped, and line breaks kept as references, which attribute normalisation would turn into spaces. */
    private fun attr(s: String): String = escape(s, inAttribute = true)

    private fun text(s: String): String = escape(s, inAttribute = false)

    /**
     * XML 1.0 escaping, dropping what XML 1.0 cannot carry at all — control characters other than tab and the
     * line breaks, lone surrogates, U+FFFE/U+FFFF. Messages quote model text, and one stray control character
     * would make the whole report unreadable to the CI system.
     */
    private fun escape(s: String, inAttribute: Boolean): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\'' -> append("&apos;")
                c == '\n' || c == '\r' || c == '\t' -> if (inAttribute) append("&#").append(c.code).append(';') else append(c)
                c < ' ' || c == '\uFFFE' || c == '\uFFFF' -> {}
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { append(c).append(s[i + 1]); i++ }
                Character.isSurrogate(c) -> {}
                else -> append(c)
            }
            i++
        }
    }
}
