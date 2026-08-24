package com.flowable.atlas

import com.flowable.atlas.graph.Findings
import com.flowable.atlas.graph.UnusedVariables
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The documentation site at `site/` must document what the code actually has.
 *
 * ## Why this exists
 * A docs site rots in one specific way: someone adds a CLI flag, a health check or an explorer route,
 * and nobody remembers the page that lists them. The page stays plausible and becomes wrong, which is
 * worse than having no page — a reader cannot tell the difference, and neither can a reviewer.
 *
 * So the lists that a reader would reasonably expect to be complete are checked against their real
 * source. Adding a flag without documenting it is a red build, not a stale page.
 *
 * Same shape as [com.flowable.atlas.render.ClaudeTemplateSyncTest] and
 * [com.flowable.atlas.render.ChangelogSyncTest]: one source of truth, a test that fails on divergence.
 * The difference is that there is nothing to re-baseline here — the fix is always to write the
 * sentence, because the point is that a human describes the new thing.
 *
 * Deliberately reads the other modules' sources as text. A test that imported `:cli` and
 * `:idea-plugin` would drag the IntelliJ platform into `:core`'s test classpath to assert on a
 * handful of string literals.
 */
class SiteDocsCoverageTest {

    private fun page(name: String): String {
        val f = File(GoldenFiles.repoRoot, "site/pages/$name.md")
        assertTrue("missing documentation page site/pages/$name.md", f.isFile)
        return f.readText()
    }

    private fun source(rel: String): String {
        val f = File(GoldenFiles.repoRoot, rel)
        assertTrue("expected to find $rel — did the file move?", f.isFile)
        return f.readText()
    }

    /** Fails with every missing item at once: fixing them one build at a time is nobody's idea of fun. */
    private fun assertDocumented(what: String, page: String, expected: Collection<String>) {
        // Case-insensitive on purpose: a page starts a sentence with a capital where the source string
        // does not, and that difference is not a documentation gap.
        val text = page(page).lowercase()
        assertTrue("no $what found in the source — this test is no longer checking anything",
            expected.isNotEmpty())
        val missing = expected.filterNot { text.contains(it.lowercase()) }
        assertTrue(
            "site/pages/$page.md does not mention ${missing.size} $what: $missing\n" +
                "Every one of them is user-visible. Document it, or the page is telling a reader " +
                "the list is complete when it is not.",
            missing.isEmpty(),
        )
    }

    @Test
    fun everyHealthCheckIsDocumented() {
        assertDocumented("health check id(s)", "checks", Findings.CHECK_ORDER)
    }

    @Test
    fun everySilenceRuleIsDocumented() {
        // Compared on a distinctive fragment rather than the whole sentence: the page is allowed to
        // punctuate a rule differently, but not to drop one.
        val fragments = UnusedVariables.SILENCE_RULES.map { rule ->
            rule.substringBefore(" —").substringBefore(", which").removePrefix("a ").take(40)
        }
        assertDocumented("variable silence rule(s)", "variables", fragments)
    }

    @Test
    fun everyCliFlagIsDocumented() {
        val flags = Regex("\"(--[a-z-]+)\" ->").findAll(source("cli/src/main/kotlin/com/flowable/atlas/cli/Main.kt"))
            .map { it.groupValues[1] }.toSortedSet()
        assertDocumented("CLI flag(s)", "cli", flags)
    }

    @Test
    fun everyExplorerRouteIsDocumented() {
        // explorer.js writes `raw==='/schema'` — three equals signs, no spaces.
        val routes = Regex("raw\\s*={2,3}\\s*'(/[a-z]+)'")
            .findAll(source("core/src/main/resources/frontend/explorer.js"))
            .map { "#" + it.groupValues[1] }.toSortedSet()
        assertDocumented("explorer route(s)", "explorer", routes)
    }

    @Test
    fun everyInspectionIsDocumented() {
        val xml = source("idea-plugin/src/main/resources/META-INF/plugin.xml")
        val names = Regex("displayName=\"([^\"]+)\"").findAll(xml)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .toSortedSet()
        // Only the inspection display names — they are what a user sees in Settings.
        val inspectionNames = Regex("<localInspection[^>]*displayName=\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] }.toSortedSet()
        val expected = if (inspectionNames.isNotEmpty()) inspectionNames else names
        assertDocumented("inspection display name(s)", "plugin-reference", expected)
    }

    @Test
    fun everyRegisteredActionIsDocumented() {
        // Action texts live in the message bundle; the descriptor holds the ids. The page lists the
        // human texts, so compare against those.
        val bundle = source("idea-plugin/src/main/resources/messages/FlowableAtlasBundle.properties")
        val texts = bundle.lineSequence()
            .filter { it.startsWith("action.") && it.contains(".text=") }
            .map { it.substringAfter(".text=").trim() }
            .filter { it.isNotBlank() && !it.contains("{") }
            .toSortedSet()
        assertDocumented("action text(s)", "plugin-reference", texts)
    }
}
