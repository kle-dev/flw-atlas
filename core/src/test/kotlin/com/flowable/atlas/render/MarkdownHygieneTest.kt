package com.flowable.atlas.render

import com.flowable.atlas.graph.Atlas
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the Markdown artifacts against Python-`repr` leakage.
 *
 * The renderers are ports of a Python original, so any value that reached the page without going
 * through a formatter printed with Python semantics: `None` for an unset field, `['total']` for a list,
 * `{'kind': 'rest', 'url': '/api/customers'}` for a map, `True`/`False` for booleans. That is noise a
 * reader has to decode, and it kept reappearing because nothing failed when it did. Now something does:
 * every list, map and optional value goes through [Fmt], and this test fails if a new call site skips it.
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
        "True" to "a boolean printed with Python casing",
        "False" to "a boolean printed with Python casing",
    )

    @Test
    fun generatedMarkdownCarriesNoPythonReprs() {
        val fixtureDir = File(javaClass.classLoader.getResource("miniproject")!!.toURI())
        val result = Atlas.extract(fixtureDir)
        val artifacts = mapOf(
            "summary.md" to SummaryRenderer.render(result, fixtureDir),
            "overview.md" to OverviewRenderer.render(result, fixtureDir),
            "CLAUDE.md" to ClaudeRenderer.render(result, fixtureDir),
        )
        val hits = ArrayList<String>()
        for ((name, text) in artifacts) {
            for ((needle, why) in forbidden) {
                for ((i, line) in text.lines().withIndex()) {
                    if (line.contains(needle)) hits.add("$name:${i + 1}  «$needle» — $why\n    $line")
                }
            }
        }
        assertTrue(
            "generated Markdown contains Python repr/None leakage:\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }
}
