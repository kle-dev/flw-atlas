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
        // The other half of the IDE bridge: a file path / file:line in the page opens the source.
        assertTrue("expected the __atlasOpen IDE bridge hook from explorer.js", html.contains("__atlasOpen"))
        assertTrue("expected the open-in-IDE button builder", html.contains("function openBtn("))
        // The graph payload is inlined — a known model key must appear.
        assertTrue("expected the substituted graph data", html.contains("orderProcess"))
        // In/out parameters survive `slimData`'s allowlist (they are nested lists, not scalars) and the
        // frontend has the code to render and search them.
        assertTrue("expected the call activity's in-mapping in the payload", html.contains("subOrderId"))
        assertTrue("expected the parameter renderer from explorer.js", html.contains("function paramSection"))
        assertTrue("expected the parameter search haystack from explorer.js", html.contains("function paramHaystack"))
        // A model's "Uses — variables & expressions" section is rebuilt in the browser from the artifact
        // nodes' `usedBy` lists; the payload does not carry `_uses` (its transpose) and must not need to.
        assertTrue("expected the usesIndex builder from explorer.js", html.contains("function usesIndex("))
        assertFalse("`_uses` leaked into the explorer payload", html.contains("\"_uses\""))
        // Provenance travels with the page: when it was generated and by which Atlas.
        assertTrue("expected generatedAt in the payload", html.contains("\"generatedAt\":\""))
        assertTrue("expected atlasVersion in the payload", html.contains("\"atlasVersion\":\""))
        assertTrue("expected the footer to render the generation time", html.contains("function stampProvenance"))
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
        // One navigation contract for every node link, in one delegated helper: ⌘/Ctrl-click and
        // middle-click open a background tab wherever a reference link appears, not just in the
        // detail panel. Each of the six link surfaces routes through it.
        assertTrue("expected the shared link wiring from explorer.js", html.contains("function wireNodeLinks("))
        assertTrue(
            "expected the detail panel to route through the shared link wiring",
            html.contains("wireNodeLinks(det, '.nc, .gn, .vlink, [data-goto]')"),
        )
        assertTrue(
            "expected the diagram card to keep the diagram open on a background open",
            html.contains("if(inModal&&!bg) closeDiagramModal()"),
        )
        // The five view renderers must not hand-roll navigation any more — a bare select() there is
        // exactly the handler that had no modifier support.
        assertFalse(
            "no view may navigate without the shared link contract",
            html.contains("select(dec(idEl.dataset.id))"),
        )
        assertTrue("expected the platform-exact modifier to be used for links", html.contains("go(t, modKey(e))"))
        // Arriving from a route that hides the strip appends a tab instead of overwriting one.
        assertTrue(
            "expected the append-on-arrival guard from explorer.js",
            html.contains("syncTabsWith(id, fromNonNodeView)"),
        )
        assertTrue(
            "expected syncTabsWith to honour the append flag",
            html.contains("else if(append || _tabsBooting"),
        )
        // …and says so, because on those routes the tab strip is not on screen to show it.
        assertTrue("expected the transient status line from explorer.html", html.contains("id=\"toast\""))
        assertTrue("expected the toast helper from explorer.js", html.contains("function toast("))
        assertTrue("expected the toast styling from explorer.css", html.contains(".toast.show{"))
        // The gesture is documented where the reference chips are.
        assertTrue("expected the reference-gesture hint from explorer.js", html.contains("class=\"relhint\""))
        assertTrue("expected the hint styling from explorer.css", html.contains(".relhint{"))
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
        // Search: the engine is wrapped in sentinels because scripts/search-selftest.mjs extracts exactly
        // that block and runs it outside the browser. Losing the markers silently disables that test, so
        // they are part of the contract — as is the injection seam that keeps the block standalone.
        assertTrue("expected the search engine start sentinel", html.contains("/*__SEARCH_CORE_START__*/"))
        assertTrue("expected the search engine end sentinel", html.contains("/*__SEARCH_CORE_END__*/"))
        assertTrue("expected the search engine's injected env", html.contains("SX_ENV.TM=TM"))
        // A query is tokenised and every word must match, in any order — the fix for "shopping template"
        // finding nothing while a "… Shopping list template" model existed.
        assertTrue("expected the query parser from explorer.js", html.contains("function qParse("))
        assertTrue("expected the haystack tokeniser from explorer.js", html.contains("function hayTokens("))
        assertTrue("expected the scorer from explorer.js", html.contains("function scoreIndex("))
        assertTrue("expected the per-term scorer from explorer.js", html.contains("function termScore("))
        assertTrue("expected the did-you-mean fallback from explorer.js", html.contains("function fuzzyScore("))
        // Hits are highlighted in both result lists, and every segment is escaped on the way out.
        assertTrue("expected the highlight splitter from explorer.js", html.contains("function hlite("))
        assertTrue("expected the shared highlight renderer from explorer.js", html.contains("function hlHtml("))
        assertTrue("expected the highlight styling from explorer.css", html.contains("mark.hl{"))
        assertTrue("expected the theme-aware highlight token from explorer.css", html.contains("--hl-bg:"))
        // Result count + two tiers of facets (section, then category inside it), and a cap you can page
        // past instead of a silent slice. Sections render in the fixed SECTIONS order — models before
        // code — so the selected row is the best-scoring hit rather than whatever landed first.
        assertTrue("expected the facet row from explorer.html", html.contains("id=\"palfacets\""))
        assertTrue("expected the facet renderer from explorer.js", html.contains("function palRenderFacets("))
        assertTrue("expected the facet styling from explorer.css", html.contains(".pal-facets{"))
        assertTrue("expected the category tier from explorer.js", html.contains("data-type=\""))
        assertTrue("expected the category tier styling from explorer.css", html.contains(".pal-frow2{"))
        assertTrue("expected Design's type wording helper from explorer.js", html.contains("function typeLabel("))
        assertTrue("expected the score-driven preselection from explorer.js", html.contains("let palAuto"))
        assertTrue("expected the pageable result cap from explorer.js", html.contains("id=\"palmore\""))
        // The bridge out of the category-scoped list filter into the everything-search.
        assertTrue("expected the list bridge renderer from explorer.js", html.contains("function renderListBridge("))
        assertTrue("expected the out-of-category counter from explorer.js", html.contains("function countOutsideCat("))
        assertTrue("expected the list bridge container from explorer.js", html.contains("id=\"lwider\""))
        assertTrue("expected the list bridge styling from explorer.css", html.contains(".lh-wider{"))
        // The index is warmed after boot so the first query does not pay for the whole deep walk.
        assertTrue("expected the index prewarm from explorer.js", html.contains("function prewarmSearchIndex("))
        // The unused-variables report: its own view, the renderer, the write/read data that survives
        // `slimData`'s allowlist, Design's wording for a write construct, and the CSS that colours the
        // two directions apart. `silenceRules` is what lets the page state its own limits.
        assertTrue("expected the unused-variables view from explorer.html", html.contains("id=\"view-variables\""))
        assertTrue("expected the unused-variables renderer from explorer.js", html.contains("function renderVariables("))
        assertTrue("expected the write/read site row from explorer.js", html.contains("function varSiteLabel("))
        assertTrue("expected the unused-variable check id in the payload", html.contains("unusedVars"))
        assertTrue("expected the write sites in the payload", html.contains("\"writeCount\""))
        assertTrue("expected Design's wording for a mapped-in parameter", html.contains("'via:inParameter'"))
        assertTrue("expected the silence rules in the payload", html.contains("silenceRules"))
        assertTrue("expected the write/read styling from explorer.css", html.contains(".vw{color:var(--bad-text)}"))
        // Node-type icons: the table, the builder and the CSS that sizes them in --ui-scale units. A bare
        // coloured dot is what these replaced; the docs mockups lift TYPE_ICONS out of explorer.js by name.
        assertTrue("expected the node-type icon table from explorer.js", html.contains("const TYPE_ICONS={"))
        assertTrue("expected the icon builder from explorer.js", html.contains("function typeIcon("))
        assertTrue("expected the icon sizing from explorer.css", html.contains(".ti{"))
        // Sidebar groups fold and are remembered; below 800px a <select> stands in for the list.
        assertTrue("expected the folded-groups store from explorer.js", html.contains("atlas-navgroups"))
        assertTrue("expected the category picker from explorer.html", html.contains("id=\"navpick\""))
        // The health list replaced the card wall on the overview, the Checks page and the variables report.
        assertTrue("expected the health list builder from explorer.js", html.contains("function healthListHtml("))
        assertTrue("expected the health list styling from explorer.css", html.contains(".hlist{"))
        assertFalse("the health card grid is gone", html.contains(".hcard"))
        // The neighborhood is a bipartite drawing inside a remembered section, no longer a radial star.
        assertTrue("expected the neighborhood section id from explorer.js", html.contains("section('neighborhood'"))
        assertTrue("expected the neighborhood styling from explorer.css", html.contains(".nb-label{"))
        assertFalse("the radial star's legend is gone", html.contains("Neighborhood — solid: uses"))
        // The IDE palette bridge: the page takes nine LaF colours from the URL and the live push.
        assertTrue("expected the IDE palette applier from explorer.js", html.contains("function applyIdePalette("))
        assertTrue("expected the idePal URL seed in explorer.html", html.contains("idePal"))
    }
}
