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
    }
}
