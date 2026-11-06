package com.flowable.atlas.render

import com.flowable.atlas.GoldenFiles
import com.flowable.atlas.graph.Atlas
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Byte-exact golden gate for [SummaryRenderer], mirroring Python's `test_summary_golden`. Runs
 * [Atlas.extract] over the `miniproject` fixture and asserts the rendered summary (plus the trailing
 * newline `print` adds) equals the committed `golden/miniproject.summary.md`.
 */
class SummaryRendererTest {

    @Test
    fun summaryGolden() {
        val fixtureDir = File(javaClass.classLoader.getResource("miniproject")!!.toURI())
        val result = Atlas.extract(fixtureDir)
        GoldenFiles.assertMatches("miniproject.summary.md", SummaryRenderer.render(result, fixtureDir) + "\n")
    }

    /**
     * The Health headline counts defects by kind, so the items under it are the defects too — a
     * warning-level one included. Listed by severity, "1 defect" was followed by nothing.
     */
    @Test
    fun aDefectThatOnlyWarnsIsNamedInTheHealthBlock() {
        val result = mapOf(
            "stats" to mapOf("models" to 1, "java" to 0, "nodes" to 0, "edges" to 0, "groups" to 0, "defects" to 1, "advice" to 1),
            "graph" to mapOf("nodes" to emptyList<Any?>(), "edges" to emptyList<Any?>()),
            "findings" to listOf(
                mapOf("check" to "gatewayNoDefault", "kind" to "defect", "severity" to "warning", "node" to "process:p",
                    "label" to "p", "element" to "gw", "message" to "`gw` has 2 conditional outgoing flows and no default"),
                mapOf("check" to "unusedForms", "kind" to "advice", "severity" to "warning", "node" to "form:f",
                    "label" to "f", "message" to "form `f` is never used"),
            ),
            "checks" to mapOf("gatewayNoDefault" to 1, "unusedForms" to 1, "open" to 2),
        )
        val health = SummaryRenderer.render(result, File("demo-project")).substringAfter("## Health — 1 defect · 1 advice")
            .substringBefore("\n\n")
        assertTrue(health, health.contains("- · `gw` has 2 conditional outgoing flows and no default — `p · gw`"))
        assertFalse(health, health.contains("never used"))
    }

    /**
     * A bot the platform ships is referenced by every action that runs it, and it is nobody's code in the
     * project — on real projects the start-case and start-process bots took hotspot slots ahead of the
     * project's own models. The fixture's platform bot ranks too low to show that, so it gets the most
     * references here, and a project model the same number as a control.
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun aPlatformBotIsNeverAHotspot() {
        val fixtureDir = File(javaClass.classLoader.getResource("miniproject")!!.toURI())
        val result = Atlas.extract(fixtureDir)
        val g = result["graph"] as MutableMap<String, Any?>
        val nodes = g["nodes"] as List<Map<String, Any?>>
        val bot = nodes.first { it["type"] == "bot" && (it["data"] as? Map<*, *>)?.get("platform") == true }
        val own = nodes.first { it["id"] == "form:orderForm" }
        val edges = (g["edges"] as List<Map<String, Any?>>).toMutableList()
        repeat(40) { i ->
            edges.add(linkedMapOf("s" to "action:DEMO-A$i", "t" to bot["id"], "rel" to "bot"))
            edges.add(linkedMapOf("s" to "action:DEMO-A$i", "t" to own["id"], "rel" to "form"))
        }
        g["edges"] = edges
        val hot = SummaryRenderer.render(result, fixtureDir).substringAfter("## Hotspots").substringBefore("\n## ")
        assertTrue("the control should rank:\n$hot", hot.contains("`${own["key"]}`"))
        assertFalse("a platform bot is listed as a hotspot:\n$hot", hot.contains("`${bot["key"]}`"))
    }
}
