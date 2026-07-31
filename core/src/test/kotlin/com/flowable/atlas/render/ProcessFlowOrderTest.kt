package com.flowable.atlas.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Execution-order rendering of a BPMN process — the shapes the `miniproject` fixture does not have:
 * a branching gateway, a join, a loop, and an element no flow mentions.
 *
 * The report used to list elements grouped by type, so it could not express any of this; the goldens
 * only cover the fixture's single straight line, hence these.
 */
class ProcessFlowOrderTest {

    private fun render(process: Map<String, Any?>): List<String> {
        val result = mapOf(
            "stats" to mapOf("models" to 1, "archives" to 0, "java" to 0, "endpoints" to 0),
            "processes" to listOf(process),
        )
        return OverviewRenderer.render(result, File("proj")).lines()
    }

    /** `- 👤 User task \`x\` …` → `x`, in the order the report lists them. */
    private fun elementOrder(lines: List<String>): List<String> =
        lines.filter { it.startsWith("- ") && "`" in it }
            .mapNotNull { Regex("`([A-Za-z0-9_]+)`").find(it)?.groupValues?.get(1) }

    private fun flow(from: String, to: String, condition: String? = null): Map<String, Any?> =
        linkedMapOf("id" to "f_${from}_$to", "from" to from, "to" to to).also {
            if (condition != null) it["condition"] = condition
        }

    @Test
    fun bothGatewayBranchesPrecedeTheJoin() {
        // start → split → (approve | reject) → join → end, declared in a deliberately unhelpful order.
        val process = mapOf(
            "key" to "p", "name" to "P", "file" to "p.bpmn",
            "userTasks" to listOf(
                mapOf("id" to "reject", "name" to "Reject"),
                mapOf("id" to "approve", "name" to "Approve"),
            ),
            "gateways" to listOf(
                mapOf("id" to "join", "name" to "Join", "type" to "parallelGateway"),
                mapOf("id" to "split", "name" to "Decide", "type" to "exclusiveGateway"),
            ),
            "events" to listOf(
                mapOf("id" to "end", "type" to "endEvent"),
                mapOf("id" to "start", "type" to "startEvent"),
            ),
            "flows" to listOf(
                flow("start", "split"),
                flow("split", "approve", "\${ok}"),
                flow("split", "reject"),
                flow("approve", "join"),
                flow("reject", "join"),
                flow("join", "end"),
            ),
        )
        val lines = render(process)
        val order = elementOrder(lines)
        assertEquals(listOf("start", "split", "approve", "reject", "join", "end"), order)

        // The branch condition is stated on the element that branches, not in a separate list.
        // (Match the gateway's own bullet — `split` also appears in the start event's `next:`.)
        val splitLine = lines.single { it.startsWith("- ◆") && it.contains("`split`") }
        assertTrue(splitLine, splitLine.contains("next:"))
        assertTrue(splitLine, splitLine.contains("`approve` [`\${ok}`]"))
        assertTrue(splitLine, splitLine.contains("`reject`"))
        // Gateways are rendered at all — they used to be parsed and dropped.
        assertTrue(splitLine, splitLine.contains("Exclusive gateway"))
    }

    @Test
    fun aLoopStillTerminatesAndListsEveryElement() {
        val process = mapOf(
            "key" to "p", "file" to "p.bpmn",
            "userTasks" to listOf(mapOf("id" to "fix"), mapOf("id" to "review")),
            "events" to listOf(
                mapOf("id" to "start", "type" to "startEvent"),
                mapOf("id" to "end", "type" to "endEvent"),
            ),
            "flows" to listOf(
                flow("start", "review"),
                flow("review", "fix", "\${!ok}"),
                flow("fix", "review"),          // back-edge: the cycle
                flow("review", "end", "\${ok}"),
            ),
        )
        val order = elementOrder(render(process))
        assertEquals(setOf("start", "review", "fix", "end"), order.toSet())
        assertEquals("start", order.first())
    }

    @Test
    fun anElementNoFlowMentionsIsMarkedUnreachable() {
        val process = mapOf(
            "key" to "p", "file" to "p.bpmn",
            "userTasks" to listOf(mapOf("id" to "orphan"), mapOf("id" to "work")),
            "events" to listOf(
                mapOf("id" to "start", "type" to "startEvent"),
                mapOf("id" to "end", "type" to "endEvent"),
            ),
            "flows" to listOf(flow("start", "work"), flow("work", "end")),
        )
        val lines = render(process)
        assertTrue(lines.single { it.contains("`orphan`") }.contains("not reachable"))
        assertTrue(lines.none { it.contains("`work`") && it.contains("not reachable") })
    }

    @Test
    fun withoutAnyFlowsNothingIsClaimedAboutOrder() {
        // Some exports carry elements but no sequence flows: no "next", and no unreachable noise either.
        val process = mapOf(
            "key" to "p", "file" to "p.bpmn",
            "userTasks" to listOf(mapOf("id" to "a"), mapOf("id" to "b")),
        )
        val lines = render(process)
        assertTrue(lines.none { it.contains("next:") })
        assertTrue(lines.none { it.contains("not reachable") })
        assertEquals(listOf("a", "b"), elementOrder(lines))
    }
}
