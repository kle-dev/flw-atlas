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
    }
}
