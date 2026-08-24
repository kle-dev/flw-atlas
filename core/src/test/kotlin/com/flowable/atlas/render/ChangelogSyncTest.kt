package com.flowable.atlas.render

import com.flowable.atlas.GoldenFiles
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `CHANGELOG.md` is the release history; the plugin descriptor's `<change-notes>` is a *window* onto its
 * most recent entries, and this test keeps the window in step with it.
 *
 * ## Why the descriptor cannot simply hold everything
 * It used to, and that was a latent release blocker: a plugin descriptor's `<change-notes>` is capped at
 * **65535 characters**, and 41 releases of notes had reached ~63 000. The next release of any size would
 * have pushed it over and made the artifact *invalid* — JetBrains' Plugin Verifier rejects it outright.
 * `./gradlew build` does not catch this; only `verifyPlugin` does, which is how it surfaced.
 *
 * So the direction is: `CHANGELOG.md` is hand-authored and complete, and the descriptor is generated from
 * its newest entries up to [ChangeNotes.BUDGET], with a pointer to the full file. That is also the better
 * reading experience — nobody scrolls 41 releases in Settings › Plugins — and it means the cap can never
 * be hit again regardless of how the history grows.
 *
 * Same shape as [ClaudeTemplateSyncTest]: one source of truth, a test that fails on divergence,
 * `./gradlew :core:updateGoldens` to re-baseline after editing `CHANGELOG.md`.
 */
class ChangelogSyncTest {

    @Test
    fun pluginChangeNotesMatchTheChangelog() {
        val releases = ChangeNotes.parseMarkdown(changelog().readText())
        assertTrue("no '## <version>' sections found in CHANGELOG.md", releases.isNotEmpty())
        GoldenFiles.assertFileMatches(pluginXml(), ChangeNotes.withChangeNotes(pluginXml().readText(), releases))
    }

    /**
     * The generated block must stay inside the descriptor's hard limit. Asserted separately from the
     * budget so that a bug in the budgeting logic fails on the real constraint, not on our own margin.
     */
    @Test
    fun changeNotesFitTheDescriptorLimit() {
        val rendered = ChangeNotes.renderHtml(ChangeNotes.parseMarkdown(changelog().readText()))
        assertTrue(
            "generated <change-notes> is ${rendered.length} chars, over the descriptor's " +
                "${ChangeNotes.HARD_LIMIT}-character maximum — lower ChangeNotes.BUDGET",
            rendered.length <= ChangeNotes.HARD_LIMIT,
        )
    }

    /** Every released version appears once — a copy-pasted heading would otherwise pass unnoticed. */
    @Test
    fun everyVersionHeadingIsUnique() {
        val versions = ChangeNotes.parseMarkdown(changelog().readText()).map { it.version }
        val duplicates = versions.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("duplicate '## <version>' heading(s) in CHANGELOG.md: $duplicates", duplicates.isEmpty())
    }

    private fun changelog() = File(GoldenFiles.repoRoot, "CHANGELOG.md")

    private fun pluginXml() = File(GoldenFiles.repoRoot, "idea-plugin/src/main/resources/META-INF/plugin.xml")
}

/**
 * Renders `CHANGELOG.md` release entries into the HTML of the plugin descriptor's `<change-notes>`.
 *
 * Not a general Markdown converter: the changelog uses one fixed shape — `## <version>` headings over
 * flat `- ` bullets with `**bold**`, `*italic*` and `` `code` `` inline — and anything else makes
 * [renderHtml] fail loudly rather than silently dropping or mangling content.
 */
internal object ChangeNotes {

    /** A plugin descriptor's `<change-notes>` may not exceed this many characters. Not our choice. */
    const val HARD_LIMIT = 65535

    /**
     * How much of the history to put in the descriptor. Far below [HARD_LIMIT] on purpose: the margin is
     * what makes a single unusually long release note harmless, and the plugin manager is not the place
     * anyone reads old history anyway.
     */
    const val BUDGET = 40_000

    private const val CHANGELOG_POINTER =
        "<p>Older releases: see <code>CHANGELOG.md</code> in the repository.</p>"

    /** Wrap width for the emitted `<li>` bodies — matches the descriptor's existing hand-written style. */
    private const val WRAP_AT = 108

    /**
     * Placeholder delimiter for lifted-out code spans. A private-use-area character: it cannot occur in
     * the changelog's prose, so unlike a `" 42 "`-style marker it can never collide with ordinary numbers
     * or perturb the surrounding spacing. Written as an escape so this file stays plain ASCII.
     */
    private const val MARK = "\uE000"

    data class Release(val version: String, val bullets: List<String>)

    // ---- markdown in --------------------------------------------------------------------------

    /** The `## <version>` sections of [markdown], in file order (newest first by convention). */
    fun parseMarkdown(markdown: String): List<Release> {
        val out = ArrayList<Release>()
        var version: String? = null
        val bullets = ArrayList<String>()
        // Bullets are hard-wrapped with a two-space continuation indent, so a line is a new bullet only
        // when it starts at column 0 with "- "; anything else indented continues the previous one.
        fun flush() { version?.let { out.add(Release(it, bullets.toList())) }; bullets.clear() }
        for (line in markdown.lines()) {
            when {
                line.startsWith("## ") -> { flush(); version = line.removePrefix("## ").trim() }
                version == null -> Unit                                  // preamble before the first release
                line.startsWith("- ") -> bullets.add(line.removePrefix("- ").trim())
                line.startsWith("  ") && bullets.isNotEmpty() ->
                    bullets[bullets.lastIndex] = bullets.last() + " " + line.trim()
                else -> Unit                                             // blank lines and stray prose
            }
        }
        flush()
        return out
    }

    // ---- html out -----------------------------------------------------------------------------

    /** [releases] as descriptor HTML, newest first, truncated at [BUDGET] with a pointer appended. */
    fun renderHtml(releases: List<Release>): String {
        val sb = StringBuilder()
        var truncated = false
        for (r in releases) {
            val block = renderRelease(r)
            if (sb.length + block.length > BUDGET) { truncated = true; break }
            sb.append(block)
        }
        if (truncated) sb.append("        ").append(CHANGELOG_POINTER).append('\n')
        return sb.toString().trimEnd('\n')
    }

    /** [pluginXml] with its `<change-notes>` CDATA replaced by [releases]; the rest is untouched. */
    fun withChangeNotes(pluginXml: String, releases: List<Release>): String {
        val re = Regex("""(<change-notes><!\[CDATA\[\n)(.*?)(\n *]]></change-notes>)""", RegexOption.DOT_MATCHES_ALL)
        require(re.containsMatchIn(pluginXml)) { "no <change-notes><![CDATA[ … ]]></change-notes> in plugin.xml" }
        return re.replace(pluginXml) { m -> m.groupValues[1] + renderHtml(releases) + m.groupValues[3] }
    }

    private fun renderRelease(r: Release): String = buildString {
        append("        <h3>").append(escape(r.version)).append("</h3>\n")
        append("        <ul>\n")
        for (b in r.bullets) append(wrap("<li>" + inline(b) + "</li>")).append('\n')
        append("        </ul>\n")
    }

    /**
     * Markdown emphasis → HTML tags. Escaping runs first so text like `a < b` survives, and the tags this
     * emits are therefore never escaped themselves.
     */
    private fun inline(md: String): String {
        var s = escape(md)
        // Code spans are lifted out FIRST and travel as placeholders. Their contents are literal —
        // `*.bpmn`, `*-models` — and the emphasis patterns below would otherwise eat those asterisks,
        // which is exactly what broke on the first run.
        val code = ArrayList<String>()
        s = Regex("""`([^`]+?)`""").replace(s) {
            code.add(it.groupValues[1]); "$MARK${code.size - 1}$MARK"
        }
        s = Regex("""\*\*(.+?)\*\*""").replace(s) { "<b>${it.groupValues[1]}</b>" }
        s = Regex("""\*([^*]+?)\*""").replace(s) { "<i>${it.groupValues[1]}</i>" }
        // Checked before the placeholders are restored, so literal markers inside code never trip it.
        require(!s.contains('*') && !s.contains('`')) {
            "unbalanced Markdown emphasis in a CHANGELOG bullet: «${s.take(160)}»"
        }
        return Regex("$MARK(\\d+)$MARK").replace(s) { "<code>${code[it.groupValues[1].toInt()]}</code>" }
    }

    /** `&`, `<`, `>` for the HTML layer. CDATA would pass them raw, but the plugin manager parses HTML. */
    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Greedy wrap matching the descriptor's style: `<li>` at 10 spaces, continuations at 14. */
    private fun wrap(line: String): String {
        val out = StringBuilder()
        var current = StringBuilder("          ")
        for (word in line.split(' ')) {
            when {
                current.isBlank() -> current.append(word)
                current.length + 1 + word.length <= WRAP_AT -> current.append(' ').append(word)
                else -> { out.append(current).append('\n'); current = StringBuilder("              ").append(word) }
            }
        }
        return out.append(current).toString()
    }
}
