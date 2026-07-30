package com.flowable.atlas.render

import com.flowable.atlas.graph.Atlas
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Smoke tests for the goldenless renderers [ClaudeRenderer] and [ExplorerHtmlRenderer]. CLAUDE.md and
 * the explorer HTML have no committed golden, so these assert the load-bearing invariants: non-empty
 * output, the expected section anchors, the frontend markers fully substituted, and the graph data
 * inlined into the island.
 */
class RenderersSmokeTest {

    private val fixtureDir: File by lazy { File(javaClass.classLoader.getResource("miniproject")!!.toURI()) }
    private val result: Map<String, Any?> by lazy { Atlas.extract(fixtureDir) }

    @Test
    fun claudeRenderHasExpectedSections() {
        val md = ClaudeRenderer.render(result, fixtureDir)
        assertTrue(md.isNotEmpty())
        for (needle in listOf(
            "# CLAUDE.md — `miniproject` (Flowable solution project)",
            "## 0. Understand this project — start here",
            "## 1. What Flowable is",
            "## 4. This project (auto-discovered by Atlas)",
            "## 5. Rules for the agent",
            "miniproject.summary.md",
            "miniproject.graph.json",
            "miniproject.explorer.html",
        )) {
            assertTrue("expected CLAUDE.md to contain: $needle", md.contains(needle))
        }
    }

    @Test
    fun explorerHtmlIsFullyComposed() {
        val html = ExplorerHtmlRenderer.render(result, fixtureDir)
        assertTrue(html.isNotEmpty())
        // The data island element id survives; the markers must all be gone.
        assertTrue("expected the atlas-data island", html.contains("id=\"atlas-data\""))
        assertFalse("leftover __ATLAS_DATA__ marker", html.contains("__ATLAS_DATA__"))
        assertFalse("leftover CSS marker", html.contains("/*__ATLAS_CSS__*/"))
        assertFalse("leftover JS marker", html.contains("/*__ATLAS_JS__*/"))
        // The generator-version stamp is substituted into the sidebar footer.
        assertFalse("leftover __ATLAS_VERSION__ marker", html.contains("__ATLAS_VERSION__"))
        assertTrue("expected the stamped Atlas version", html.contains(">Atlas "))
        // The CSS token scale survives composition into the single-file HTML.
        assertTrue("expected the layout-scale tokens from explorer.css", html.contains("--space-1:"))
        // The IDE theme-bridge hook the IntelliJ JCEF viewer pushes theme switches through.
        assertTrue("expected the __atlasSetIdeTheme hook from explorer.js", html.contains("__atlasSetIdeTheme"))
        // The copy-to-clipboard machinery: the shared helper + the icon-button class + the IDE bridge hook.
        assertTrue("expected the atlasCopy helper from explorer.js", html.contains("function atlasCopy"))
        assertTrue("expected the copy-button class from explorer.js/css", html.contains("class=\"cpy\"") || html.contains(".cpy{"))
        assertTrue("expected the __atlasCopy IDE bridge hook from explorer.js", html.contains("__atlasCopy"))
        // The graph payload is inlined — a known model key must appear.
        assertTrue("expected the substituted graph data", html.contains("orderProcess"))
        // In/out parameters survive `slimData`'s allowlist (they are nested lists, not scalars) and the
        // frontend has the code to render and search them.
        assertTrue("expected the call activity's in-mapping in the payload", html.contains("subOrderId"))
        assertTrue("expected the parameter renderer from explorer.js", html.contains("function paramSection"))
        assertTrue("expected the parameter search haystack from explorer.js", html.contains("function paramHaystack"))
        assertTrue("expected the parameter direction styling from explorer.css", html.contains(".parmgrid .pd{"))
        // A form button's payload mapping reaches the page — the name the action script reads back.
        assertTrue("expected the action button's payload key in the payload", html.contains("customerEmail"))
        // Detail sections are collapsible, and a search hit scrolls/highlights the row it matched.
        assertTrue("expected the section helper from explorer.js", html.contains("function section("))
        assertTrue("expected the section styling from explorer.css", html.contains("details.sect>summary"))
        assertTrue("expected the search-focus helper from explorer.js", html.contains("function applyFocus("))
        // The diagram is zoomable and can be opened full screen.
        assertTrue("expected the diagram zoom controller from explorer.js", html.contains("function wireDiagram("))
        assertTrue("expected the fullscreen diagram overlay from explorer.html", html.contains("id=\"dgmodal\""))
        assertTrue("expected the diagram viewport styling from explorer.css", html.contains(".dgview{"))
        // Design's vocabulary, with the tooltip that explains each term.
        assertTrue("expected the Design term table from explorer.js", html.contains("const DESIGN_TERMS"))
        assertTrue("expected Design's wording for a payload mapping", html.contains("Send payload map"))
        assertTrue("expected Design's wording for a decision model", html.contains("Decision tables"))
        assertTrue("expected the term styling from explorer.css", html.contains(".term[title]{"))
        // Script syntax findings survive `slimData` (nested `problems` under scriptTasks) and reach
        // the health object + Checks tab; the fixture's broken script task pins the whole path.
        assertTrue("expected the scriptIssues health key in explorer.js", html.contains("scriptIssues"))
        assertTrue("expected the fixture's script finding in the payload", html.contains("'(' is never closed"))
        assertTrue("expected the script-syntax checks block from explorer.js", html.contains("Script syntax findings"))
        // The script code viewer: syntax highlighting + line numbers + problem-line marking.
        assertTrue("expected the script highlighter from explorer.js", html.contains("function hlScript"))
        assertTrue("expected the code viewer from explorer.js", html.contains("function codeBoxHtml"))
        assertTrue("expected the token styling from explorer.css", html.contains(".tok-k{"))
        assertTrue("expected the problem-line styling from explorer.css", html.contains(".cl-bad{"))
        // A form/page REST button's endpoint reaches the page (it used to live only in the shared Ctx,
        // which the payload never carried) and is both rendered and searchable.
        assertTrue("expected the REST button's endpoint in the payload", html.contains("/canEdit"))
        assertTrue("expected the REST calls section from explorer.js", html.contains("'restcalls'"))
        // The ⌘K palette is resizable from its corner and remembers the size the user settles on.
        assertTrue("expected the palette resize wiring from explorer.js", html.contains("function wirePaletteResize("))
        assertTrue("expected the palette size store from explorer.js", html.contains("atlas-palette"))
        assertTrue("expected the palette resize affordance from explorer.css", html.contains("resize:both"))
        assertTrue("expected the palette width token from explorer.css", html.contains("--pal-w:"))
        // Detail tabs: the strip markup and the tab model. One reused panel (role=tabpanel) plus a
        // tablist that holds nothing but tabs; the tab set is remembered, so its store key and the
        // project scoping are part of the contract.
        assertTrue("expected the tab strip from explorer.html", html.contains("id=\"dtabs\""))
        assertTrue("expected the detail wrapper from explorer.html", html.contains("class=\"detailwrap\""))
        assertTrue("expected the single reused detail panel", html.contains("role=\"tabpanel\""))
        assertTrue("expected the tablist from explorer.js", html.contains("role=\"tablist\""))
        assertTrue("expected the tab renderer from explorer.js", html.contains("function renderTabs("))
        assertTrue("expected the tab opener from explorer.js", html.contains("function openTabs("))
        assertTrue("expected the tab closer from explorer.js", html.contains("function closeTab("))
        assertTrue("expected the tab/hash reconciliation from explorer.js", html.contains("function syncTabsWith("))
        assertTrue("expected the tab store key from explorer.js", html.contains("atlas-tabs"))
        assertTrue("expected the tab store to be project-scoped", html.contains("p:DATA.project"))
        assertTrue("expected the tab styling from explorer.css", html.contains(".dtab.on{"))
        assertTrue("expected the scrolling tablist from explorer.css", html.contains(".dtablist{"))
        // Multi-selection in both result lists: state, the open action, and the mark styling. Marks
        // use aria-checked so aria-selected can keep meaning "this is the node on screen".
        assertTrue("expected the list multi-select state from explorer.js", html.contains("let listMarks"))
        assertTrue("expected the palette multi-select state from explorer.js", html.contains("let palMarks"))
        assertTrue("expected the list open-marked action from explorer.js", html.contains("function openMarkedList("))
        assertTrue("expected the palette open-marked action from explorer.js", html.contains("function openMarkedPal("))
        assertTrue("expected the mark repaint without scroll from explorer.js", html.contains("function syncListMarks("))
        assertTrue("expected marks to be exposed as aria-checked", html.contains("aria-checked"))
        assertTrue("expected the list mark styling from explorer.css", html.contains(".item.mark{"))
        assertTrue("expected the palette mark styling from explorer.css", html.contains(".pal-item.mark{"))
        assertTrue("expected the palette multi-select footer from explorer.html", html.contains("id=\"palfoot\""))
    }
}
