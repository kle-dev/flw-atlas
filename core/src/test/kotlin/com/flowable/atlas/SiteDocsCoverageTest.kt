package com.flowable.atlas

import com.flowable.atlas.graph.CheckCatalog
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
        assertDocumented("health check id(s)", "checks", CheckCatalog.ORDER)
    }

    /** The site's own anchor rule (`slugify` in scripts/site-build.mjs), so the slug the catalog names is the one the page has. */
    private fun slugify(text: String): String = text.lowercase()
        .replace(Regex("<[^>]+>"), "").replace(Regex("[`*]"), "")
        .replace(Regex("[^\\w\\- ]+"), "").trim().replace(Regex("\\s+"), "-")

    /** A check's `docs` slug is a link on every surface; a renamed heading must be a red build, not a dead anchor. */
    @Test
    fun everyCheckDocsAnchorExists() {
        val headings = Regex("^#{2,3} (.+)$", RegexOption.MULTILINE).findAll(page("checks"))
            .map { slugify(it.groupValues[1]) }.toSet()
        val dead = CheckCatalog.CHECKS.filter { it.docs !in headings }.map { "${it.id} → #${it.docs}" }
        assertTrue("CheckCatalog names heading anchor(s) site/pages/checks.md does not have: $dead", dead.isEmpty())
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

    /** The long flags Main.kt parses (`"--x" ->`) and the short ones (`'x' ->` in the cluster loop). */
    private fun cliFlags(): Pair<Set<String>, Set<String>> {
        val main = source("cli/src/main/kotlin/com/flowable/atlas/cli/Main.kt")
        val long = Regex("\"(--[a-z-]+)\" ->").findAll(main).map { it.groupValues[1] }.toSortedSet()
        val short = Regex("'([a-z])' ->").findAll(main).map { "-" + it.groupValues[1] }.toSortedSet()
        return long to short
    }

    @Test
    fun everyCliFlagIsDocumented() {
        val (long, short) = cliFlags()
        assertDocumented("CLI flag(s)", "cli", long + short)
    }

    /**
     * The other direction: a flag the page still lists but the CLI no longer parses is a lie a reader
     * acts on. The launcher's own flags (`--no-open`) are documented on the page and are not the jar's.
     */
    @Test
    fun noDocumentedCliFlagIsGone() {
        val (long, short) = cliFlags()
        val launcherFlags = setOf("--no-open")
        val documented = Regex("`(-{1,2}[a-z][a-z-]*)`").findAll(page("cli"))
            .map { it.groupValues[1] }.toSortedSet() - launcherFlags - "--"
        // a short cluster (`-vq`) is fine when every letter in it is a short flag
        val isCluster = { f: String -> f.length > 2 && !f.startsWith("--") && f.drop(1).all { c -> "-$c" in short } }
        val gone = documented.filterNot { it in long || it in short || isCluster(it) }
        assertTrue("site/pages/cli.md documents flag(s) the CLI does not parse: $gone", gone.isEmpty())
    }

    @Test
    fun everyExplorerRouteIsDocumented() {
        assertDocumented("explorer route(s)", "explorer", explorerRoutes())
    }

    private fun explorerRoutes(): Set<String> {
        val js = source("core/src/main/resources/frontend/explorer.js")
        // explorer.js writes `raw==='/overview'` for the dashboard and lists the report routes as the
        // keys of `REPORT_VIEWS={'/schema':'schema', …}`.
        val direct = Regex("raw\\s*={2,3}\\s*'(/[a-z]+)'").findAll(js).map { it.groupValues[1] }
        val reports = Regex("'(/[a-z]+)'\\s*:\\s*'[a-z]+'").findAll(js).map { it.groupValues[1] }
        return (direct + reports).map { "#$it" }.toSortedSet()
    }

    /** A route the page lists that the router no longer answers sends the reader to the overview. */
    @Test
    fun noDocumentedExplorerRouteIsGone() {
        val documented = Regex("`(#/[a-z]+)`").findAll(page("explorer")).map { it.groupValues[1] }.toSortedSet()
        val gone = documented.filterNot { it in explorerRoutes() || it == "#/browse" }
        assertTrue("site/pages/explorer.md documents route(s) explorer.js does not route: $gone", gone.isEmpty())
    }

    /** A check id in the checks table that Findings no longer produces would be a count of nothing. */
    @Test
    fun noDocumentedHealthCheckIsGone() {
        val documented = Regex("^\\| `([a-zA-Z]+)` \\|", RegexOption.MULTILINE).findAll(page("checks"))
            .map { it.groupValues[1] }.toSortedSet()
        assertTrue("no check ids found in the checks table — did the table change shape?", documented.isNotEmpty())
        val gone = documented.filterNot { it in CheckCatalog.ORDER }
        assertTrue("site/pages/checks.md lists check(s) Findings does not produce: $gone", gone.isEmpty())
    }

    @Test
    fun everySearchFacetIsDocumented() {
        // The inline `t:` / `key:` / `label:` prefixes are the one part of the search a reader cannot
        // discover by typing, so the page has to list all of them — aliases included.
        val js = source("core/src/main/resources/frontend/explorer.js")
        val block = Regex("const SX_FACET_KEYS=\\{([^}]*)\\}").find(js)?.groupValues?.get(1)
        assertTrue("SX_FACET_KEYS not found in explorer.js — did the search facets move?", block != null)
        val prefixes = Regex("(\\w+):'").findAll(block!!).map { it.groupValues[1] + ":" }.toSortedSet()
        assertDocumented("search facet prefix(es)", "explorer", prefixes)
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
        // human texts, so compare against those. A group's text too: a submenu such as *Open Environment in
        // Browser* is an entry a reader looks up like any action, and reading `action.` keys alone let one
        // go unlisted.
        val bundle = source("idea-plugin/src/main/resources/messages/FlowableAtlasBundle.properties")
        val texts = bundle.lineSequence()
            .filter { (it.startsWith("action.") || it.startsWith("group.")) && it.contains(".text=") }
            .map { it.substringAfter(".text=").trim() }
            .filter { it.isNotBlank() && !it.contains("{") }
            .toSortedSet()
        assertDocumented("action or group text(s)", "plugin-reference", texts)
    }

    /** The `<description>` block of the plugin descriptor — what the plugin manager shows before an install. */
    private fun pluginDescription(): String {
        val xml = source("idea-plugin/src/main/resources/META-INF/plugin.xml")
        val start = xml.indexOf("<description><![CDATA[")
        val end = xml.indexOf("]]></description>")
        assertTrue("no <description> CDATA block in plugin.xml", start >= 0 && end > start)
        return xml.substring(start, end)
    }

    @Test
    fun pluginDescriptionNamesEveryToolWindow() {
        // The description is hand-written and nothing else held it to the descriptor: Atlas Findings, a
        // whole tool window, went unmentioned in the text the plugin manager shows. A tool window is the
        // one kind of feature that is never a detail.
        val xml = source("idea-plugin/src/main/resources/META-INF/plugin.xml")
        val ids = Regex("<toolWindow\\s+id=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toSortedSet()
        assertTrue("no <toolWindow> found in plugin.xml — this test is no longer checking anything", ids.isNotEmpty())
        val description = pluginDescription()
        val missing = ids.filterNot { description.contains(it) }
        assertTrue("the plugin's <description> does not name the tool window(s) $missing", missing.isEmpty())
    }

    @Test
    fun pluginDescriptionSpellsSettingsPathsInFull() {
        // "Settings → Generation" names a page nobody can find: the page is under Tools → Flowable Atlas.
        // The prose wraps, so a path may break across lines: whitespace, not a single space, between parts.
        val shortened = Regex("Settings\\s+→(?!\\s*(?:Tools\\s+→\\s+Flowable\\s+Atlas|Editor|Plugins|Appearance))[^<.,;]*")
            .findAll(pluginDescription()).map { it.value }.toList()
        assertTrue("the plugin's <description> shortens a settings path: $shortened", shortened.isEmpty())
    }

    @Test
    fun everyNotificationGroupIsDocumentedByItsDisplayName() {
        // Settings → Notifications lists a group by its bundle text, never by its id; the reference named
        // the ids, which a reader looking for them there could not find.
        val xml = source("idea-plugin/src/main/resources/META-INF/plugin.xml")
        val bundle = source("idea-plugin/src/main/resources/messages/FlowableAtlasBundle.properties")
            .lineSequence().filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        val groups = Regex("<notificationGroup\\s+id=\"([^\"]+)\"[^>]*key=\"([^\"]+)\"").findAll(xml)
            .map { it.groupValues[1] to bundle.getValue(it.groupValues[2]) }.toList()
        assertTrue("no <notificationGroup> with a key found in plugin.xml", groups.isNotEmpty())
        val page = page("plugin-reference")
        val section = page.substringAfter("\n## Notifications").substringBefore("\n## ")
        assertTrue("plugin-reference.md has no ## Notifications section", section.length < page.length)
        for ((id, display) in groups) {
            assertTrue("## Notifications does not name the group '$display'", section.contains(display))
            if (id != display) assertTrue("## Notifications names the group by its id '$id', which Settings never shows", !section.contains("**$id**"))
        }
    }
}
