package com.flowable.atlas.render

import com.flowable.atlas.GoldenFiles
import com.flowable.atlas.graph.Atlas
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The structural guarantee behind [ExplorerHtmlRenderer]'s payload projection: every *container*
 * key a parser puts into a graph node's `data` must be either allowlisted for the explorer
 * ([ExplorerHtmlRenderer.FRONTEND_DATA_KEYS] — visible and searchable) or consciously stripped with
 * a reason ([ExplorerHtmlRenderer.STRIPPED_DATA_KEYS]). Scalars always survive the projection, so
 * they need no bookkeeping.
 *
 * Without this, a new parser field silently vanished between `extract()` and the report — parsed,
 * present in graph.json, and invisible in the explorer (the audit found six such keys). Now the
 * build fails until the developer decides which set the key belongs to.
 */
class PayloadCompletenessTest {

    @Test
    fun everyContainerDataKeyIsADecision() {
        val roots = listOf(
            fixtureDir(),
            File(GoldenFiles.repoRoot, "site/flowable-demo"),
        )
        val undeclared = sortedMapOf<String, MutableSet<String>>() // key -> node types carrying it
        for (root in roots) {
            val graph = Atlas.extract(root)["graph"] as Map<*, *>
            for (node in graph["nodes"] as List<*>) {
                val nm = node as Map<*, *>
                val data = nm["data"] as? Map<*, *> ?: continue
                for ((k, v) in data) {
                    if (v == null || v is String || v is Boolean || v is Number) continue // scalar: survives
                    val key = k.toString()
                    if (key in ExplorerHtmlRenderer.FRONTEND_DATA_KEYS) continue
                    if (key in ExplorerHtmlRenderer.STRIPPED_DATA_KEYS) continue
                    undeclared.getOrPut(key) { sortedSetOf() }.add(nm["type"].toString())
                }
            }
        }
        assertTrue(
            "parser emits container data keys the explorer payload silently drops — add each to " +
                "ExplorerHtmlRenderer.FRONTEND_DATA_KEYS (visible + searchable) or, with a reason, to " +
                "STRIPPED_DATA_KEYS:\n" +
                undeclared.entries.joinToString("\n") { (k, types) -> "  $k (on ${types.joinToString(", ")})" },
            undeclared.isEmpty(),
        )
    }

    private fun fixtureDir(): File {
        val url = javaClass.classLoader.getResource("miniproject")
            ?: error("miniproject fixture not on the test classpath")
        return File(url.toURI())
    }
}
