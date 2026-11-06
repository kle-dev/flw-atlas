package com.flowable.atlas.render

import com.flowable.atlas.GoldenFiles
import com.flowable.atlas.graph.Atlas
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the Markdown artifacts against Python-`repr` leakage and against Markdown that does not render
 * as what it says.
 *
 * The renderers are ports of a Python original, so any value that reached the page without going
 * through a formatter printed with Python semantics: `None` for an unset field, `['total']` for a list,
 * `{'kind': 'rest', 'url': '/api/customers'}` for a map, `True`/`False` for booleans. That is noise a
 * reader has to decode, and it kept reappearing because nothing failed when it did. Now something does:
 * every list, map and optional value goes through [Fmt], and this test fails if a new call site skips it.
 *
 * It runs over the test fixture **and the public sample project**: run over the fixture alone, it passed
 * while the published sample's overview printed `None` for a decision without a table and a database
 * operation, and `requiredRule=True` for a plan item — shapes the fixture does not have.
 *
 * Scope note: the goldens pin the *exact* wording, but only for what exists today — this test states the
 * rule, so a newly rendered field is caught even before anyone looks at a golden diff.
 */
class MarkdownHygieneTest {

    /** Python `str()`/`repr()` fingerprints that must never reach a generated artifact. */
    private val forbidden = listOf(
        "None" to "an unset value — omit the label instead (Fmt.opt / Fmt.fields)",
        "{'" to "a map printed with repr() — render what it means (Fmt.dataSource / Fmt.fields)",
        "['" to "a list printed with repr() — use Fmt.list / Fmt.codeList",
        "implements []" to "an empty list printed with repr() — leave the label out",
        "True" to "a boolean printed with Python casing",
        "False" to "a boolean printed with Python casing",
    )

    @Test
    fun theFixturesMarkdownCarriesNoPythonReprs() =
        check(File(javaClass.classLoader.getResource("miniproject")!!.toURI()))

    @Test
    fun theSampleProjectsMarkdownCarriesNoPythonReprs() = check(File(GoldenFiles.repoRoot, "site/flowable-demo"))

    private fun artifacts(dir: File): Map<String, String> {
        val result = Atlas.extract(dir)
        return mapOf(
            "summary.md" to SummaryRenderer.render(result, dir),
            "overview.md" to OverviewRenderer.render(result, dir),
            "CLAUDE.md" to ClaudeRenderer.render(result, dir),
            "playwright.md" to PlaywrightRenderer.render(result, dir),
            "playwright-map.md" to PlaywrightRenderer.renderMap(result, dir),
        )
    }

    private fun check(dir: File) {
        val hits = ArrayList<String>()
        for ((name, text) in artifacts(dir)) {
            val lines = text.lines()
            for ((needle, why) in forbidden) {
                for ((i, line) in lines.withIndex()) {
                    if (line.contains(needle)) hits.add("$name:${i + 1}  «$needle» — $why\n    $line")
                }
            }
            // Indented four or more, a line opens a code block wherever it cannot continue something: after a
            // blank line or a heading, outside a list. The case plan tree started that way under its `###`
            // heading, and every Markdown reader showed it as literal text.
            var fence = false
            var inList = false
            var opensBlock = true
            for ((i, line) in lines.withIndex()) {
                val t = line.trimStart()
                if (t.startsWith("```") || t.startsWith("~~~")) { fence = !fence; opensBlock = !fence; continue }
                if (fence) continue
                if (line.isBlank()) { opensBlock = true; continue }
                val indent = line.length - t.length
                when {
                    indent >= 4 && !inList && opensBlock ->
                        hits.add("$name:${i + 1}  an indented line no list or paragraph continues — a code block\n    $line")
                    t.startsWith("#") -> inList = false
                    LIST_ITEM.containsMatchIn(line) -> inList = true
                    indent == 0 && opensBlock -> inList = false
                }
                opensBlock = t.startsWith("#")
                // Text that looks like a tag outside a code span is HTML to CommonMark, and a preview drops
                // or renders it: a form expression building `"<strong>To:</strong>"` reached a real report
                // as markup. (PlaywrightRendererTest holds the guide's hand-written half to the same rule.)
                val bare = line.replace(CODE_SPAN, "")
                TAG.find(bare)?.let { hits.add("$name:${i + 1}  «${it.value}» outside a code span is HTML to Markdown\n    $line") }
            }
        }
        assertTrue(
            "generated Markdown of ${dir.name} carries repr leakage or Markdown that renders as something else:\n" +
                hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    private companion object {
        val LIST_ITEM = Regex("^\\s*(?:[-*+]|\\d+\\.)\\s")
        val CODE_SPAN = Regex("(`+)[\\s\\S]*?\\1")
        /** An HTML open or self-closing tag; an autolink (`<https://…>`) has a `:` and does not match. */
        val TAG = Regex("<[A-Za-z][A-Za-z0-9-]*(\\s[^<>]*)?/?>")
    }
}
