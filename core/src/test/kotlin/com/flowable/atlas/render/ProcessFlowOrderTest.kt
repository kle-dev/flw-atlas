package com.flowable.atlas.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * A boundary event has no incoming flow, so it used to be a seed of the walk — listed right after the
     * start event, before the task it hangs on, without naming that task, and "not reachable" when no flow
     * left it either.
     */
    @Test
    fun aBoundaryEventFollowsItsHostAndSaysWhichItIs() {
        val process = mapOf(
            "key" to "p", "file" to "p.bpmn",
            "userTasks" to listOf(mapOf("id" to "idle"), mapOf("id" to "t1", "name" to "Work"), mapOf("id" to "esc", "name" to "Escalate")),
            "events" to listOf(
                mapOf("id" to "b1", "type" to "boundaryEvent", "def" to "timer", "value" to "PT1H",
                    "attachedTo" to "t1", "cancelActivity" to "false"),
                mapOf("id" to "b2", "type" to "boundaryEvent", "def" to "error", "attachedTo" to "t1"),
                mapOf("id" to "b3", "type" to "boundaryEvent", "def" to "error", "attachedTo" to "idle"),
                mapOf("id" to "start", "type" to "startEvent"),
                mapOf("id" to "end", "type" to "endEvent"),
                mapOf("id" to "end2", "type" to "endEvent"),
            ),
            "flows" to listOf(flow("start", "t1"), flow("t1", "end"), flow("b1", "esc"), flow("esc", "end2")),
        )
        val lines = render(process)
        val order = elementOrder(lines)
        assertEquals(listOf("start", "t1", "b1", "b2"), order.take(4))
        assertTrue(order.toString(), order.indexOf("b1") < order.indexOf("esc"))
        val b1 = lines.single { it.contains("`b1`") && it.startsWith("- 🔔") }
        assertTrue(b1, b1.contains("on `t1` (non-interrupting)"))
        assertFalse(b1, b1.contains("not reachable"))
        // no flow leaves b2, but its host is reached — and an interrupting event is the default, unmarked
        val b2 = lines.single { it.contains("`b2`") }
        assertTrue(b2, b2.contains("on `t1`") && !b2.contains("non-interrupting") && !b2.contains("not reachable"))
        // the host's `next:` is still its real flows
        assertTrue(lines.single { it.startsWith("- 👤") && it.contains("`t1`") }.endsWith("next: `end`"))
        // a host no flow reaches stays after the walk, and what hangs on it is as unreachable as it is
        assertEquals(listOf("idle", "b3"), order.takeLast(2))
        assertTrue(lines.single { it.contains("`b3`") }.contains("not reachable"))
    }

    @Test
    fun anEventSubProcessStartsOnItsEventAndFollowsTheMainFlow() {
        val process = mapOf(
            "key" to "p", "file" to "p.bpmn",
            "subProcesses" to listOf(mapOf("id" to "onCancel", "name" to "On cancel", "type" to "subProcess", "eventSubProcess" to true)),
            "userTasks" to listOf(mapOf("id" to "undo", "parent" to "onCancel"), mapOf("id" to "work")),
            "events" to listOf(
                mapOf("id" to "cancelStart", "type" to "startEvent", "def" to "message", "value" to "cancel", "parent" to "onCancel"),
                mapOf("id" to "start", "type" to "startEvent"),
                mapOf("id" to "end", "type" to "endEvent"),
            ),
            "flows" to listOf(flow("cancelStart", "undo"), flow("start", "work"), flow("work", "end")),
        )
        val lines = render(process)
        assertEquals(listOf("start", "work", "end", "onCancel", "cancelStart", "undo"), elementOrder(lines))
        val sub = lines.single { it.contains("`onCancel`") && it.startsWith("- ▣") }
        assertTrue(sub, sub.contains("starts on its event") && !sub.contains("not reachable"))
    }

    /**
     * A loop stalls the walk at its join, and the event sub-process — parked because no flow enters it —
     * was the next thing in the frontier: on a real project it opened in the middle of the main flow.
     */
    @Test
    fun anEventSubProcessWaitsForTheMainFlowEvenWhenThatLoops() {
        val process = mapOf(
            "key" to "p", "file" to "p.bpmn",
            "subProcesses" to listOf(mapOf("id" to "onCancel", "type" to "subProcess", "eventSubProcess" to true)),
            "userTasks" to listOf(mapOf("id" to "undo", "parent" to "onCancel"), mapOf("id" to "work"), mapOf("id" to "fix")),
            "gateways" to listOf(mapOf("id" to "join", "type" to "exclusiveGateway"), mapOf("id" to "ok", "type" to "exclusiveGateway")),
            "events" to listOf(
                mapOf("id" to "cancelStart", "type" to "startEvent", "def" to "signal", "parent" to "onCancel"),
                mapOf("id" to "start", "type" to "startEvent"),
                mapOf("id" to "end", "type" to "endEvent"),
            ),
            "flows" to listOf(
                flow("cancelStart", "undo"),
                flow("start", "join"), flow("join", "work"), flow("work", "ok"),
                flow("ok", "end", "\${done}"), flow("ok", "fix"), flow("fix", "join"),
            ),
        )
        assertEquals(listOf("start", "join", "work", "ok", "end", "fix", "onCancel", "cancelStart", "undo"),
            elementOrder(render(process)))
    }

    /** An embedded sub-process runs start to end before the flow leaves it; its contents used to open the process. */
    @Test
    fun anEmbeddedSubProcessListsItsContentsBeforeWhatFollowsIt() {
        val process = mapOf(
            "key" to "p", "file" to "p.bpmn",
            "subProcesses" to listOf(mapOf("id" to "review", "type" to "subProcess", "eventSubProcess" to false)),
            "userTasks" to listOf(mapOf("id" to "check", "parent" to "review"), mapOf("id" to "archive")),
            "events" to listOf(
                mapOf("id" to "aStart", "type" to "startEvent", "parent" to "review"),
                mapOf("id" to "aEnd", "type" to "endEvent", "parent" to "review"),
                mapOf("id" to "start", "type" to "startEvent"),
                mapOf("id" to "end", "type" to "endEvent"),
            ),
            "flows" to listOf(
                flow("aStart", "check"), flow("check", "aEnd"),
                flow("start", "review"), flow("review", "archive"), flow("archive", "end"),
            ),
        )
        assertEquals(listOf("start", "review", "aStart", "check", "aEnd", "archive", "end"), elementOrder(render(process)))
    }

    @Test
    fun aMultiInstanceLineSaysParallelOrSequentialAndWhatItCounts() {
        val process = mapOf(
            "key" to "p", "file" to "p.bpmn",
            "userTasks" to listOf(mapOf("id" to "a"), mapOf("id" to "b")),
            "multiInstance" to listOf(
                mapOf("activity" to "a", "sequential" to "true", "cardinality" to "3"),
                mapOf("activity" to "b", "sequential" to "false", "collection" to "\${items}",
                    "completionCondition" to "\${nrOfCompletedInstances >= 2}"),
            ),
        )
        val lines = render(process)
        assertTrue(lines.toString(), lines.contains("    - 🔁 Multi-instance (sequential) · cardinality: `3`"))
        assertTrue(lines.toString(), lines.contains("    - 🔁 Multi-instance (parallel) · collection: `\${items}` · " +
            "completes when: `\${nrOfCompletedInstances >= 2}`"))
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
