package com.flowable.atlas.render

import com.flowable.atlas.GoldenFiles
import com.flowable.atlas.graph.Atlas
import com.flowable.atlas.model.Dyn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A storyline nests what the flows only chain: the shapes below are the ones where the nesting can be
 * wrong while every element is still listed — a join, a branch that ends, a loop, a boundary path, a
 * call. The `miniproject` fixture is one straight line and its case has no criteria, so its golden pins
 * the file's layout and these pin what it says.
 */
class FlowStoriesTest {

    private fun flow(from: String, to: String, condition: String? = null, name: String? = null, default: Boolean = false) =
        linkedMapOf<String, Any?>("id" to "f_${from}_$to", "from" to from, "to" to to).also {
            if (condition != null) it["condition"] = condition
            if (name != null) it["name"] = name
            if (default) it["default"] = true
        }

    private fun task(id: String) = mapOf("id" to id, "name" to id.replaceFirstChar { it.uppercase() })
    private fun event(id: String, type: String, vararg more: Pair<String, Any?>) = mapOf("id" to id, "type" to type) + more
    private fun gateway(id: String, type: String = "exclusiveGateway") = mapOf("id" to id, "type" to type)

    private fun process(key: String, vararg buckets: Pair<String, Any?>): Map<String, Any?> =
        mapOf("key" to key, "name" to key.uppercase(), "file" to "$key.bpmn") + buckets

    private fun stories(vararg processes: Map<String, Any?>, cases: List<Map<String, Any?>> = emptyList()) =
        FlowStories.build(mapOf("processes" to processes.toList(), "cases" to cases))

    private fun steps(story: Map<String, Any?>) = Dyn.list(story["steps"]).map { Dyn.map(it) }

    /**
     * A story as one line: ids in order, a gateway's branches in `[…|…]` each led by its condition, name
     * or `else`, anything else that holds steps in `(…)`, a loop as `↺id`.
     */
    private fun outline(steps: List<Map<String, Any?>>): String = steps.joinToString(" ") { s ->
        val id = s["id"].toString()
        val inner = Dyn.listOrNull(s["steps"])?.let { "(" + outline(it.map(Dyn::map)) + ")" } ?: ""
        val branches = Dyn.listOrNull(s["branches"])?.joinToString("|", "[", "]") { bAny ->
            val b = Dyn.map(bAny)
            val label = (b["condition"] ?: b["name"] ?: if (b["default"] == true) "else" else "").toString()
            (label + ": " + outline(Dyn.list(b["steps"]).map(Dyn::map))).trim()
        } ?: ""
        (if (s["kind"] == "loop") "↺" else "") + id + inner + branches
    }

    @Test
    fun whatFollowsAJoinFollowsTheGatewayAndNotItsFirstBranch() {
        val p = process("p",
            "userTasks" to listOf(task("approve"), task("reject"), task("archive")),
            "gateways" to listOf(gateway("split"), gateway("join")),
            "events" to listOf(event("start", "startEvent"), event("end", "endEvent")),
            "flows" to listOf(flow("start", "split"), flow("split", "approve", "\${ok}"), flow("split", "reject", default = true),
                flow("approve", "join"), flow("reject", "join"), flow("join", "archive"), flow("archive", "end")),
        )
        // The join gateway is not a step: it has one way out, and the nesting already says "they meet here".
        assertEquals("start split[\${ok}: approve|else: reject] archive end", outline(steps(stories(p).single())))
    }

    @Test
    fun aBranchThatEndsTheProcessLeavesTheRestToTheOther() {
        val p = process("p",
            "userTasks" to listOf(task("work")),
            "gateways" to listOf(gateway("split")),
            "events" to listOf(event("start", "startEvent"), event("rejected", "endEvent"), event("done", "endEvent")),
            "flows" to listOf(flow("start", "split"), flow("split", "rejected", name = "no"), flow("split", "work", name = "yes"),
                flow("work", "done")),
        )
        assertEquals("start split[no: rejected|yes: work done]", outline(steps(stories(p).single())))
    }

    @Test
    fun aBranchThatSkipsAheadIsEmptyAndTheStepItSkipsToIsSharedByBoth() {
        val p = process("p",
            "userTasks" to listOf(task("extra"), task("always")),
            "gateways" to listOf(gateway("split")),
            "events" to listOf(event("start", "startEvent"), event("end", "endEvent")),
            "flows" to listOf(flow("start", "split"), flow("split", "extra", "\${more}"), flow("split", "always", default = true),
                flow("extra", "always"), flow("always", "end")),
        )
        assertEquals("start split[\${more}: extra|else:] always end", outline(steps(stories(p).single())))
    }

    @Test
    fun aLoopIsOneStepNamingWhereItReturnsTo() {
        val p = process("p",
            "userTasks" to listOf(task("fill"), task("rework")),
            "gateways" to listOf(gateway("check")),
            "events" to listOf(event("start", "startEvent"), event("end", "endEvent")),
            // the default flow first in the file: it is still told last
            "flows" to listOf(flow("start", "fill"), flow("fill", "check"), flow("check", "end", default = true),
                flow("check", "rework", "\${!valid}"), flow("rework", "fill")),
        )
        val story = stories(p).single()
        // The way back is not a path to the end: reading it as one made "end" the join and emptied the default branch.
        assertEquals("start fill check[\${!valid}: rework ↺fill|else: end]", outline(steps(story)))
        assertNull(story["warnings"])
    }

    @Test
    fun aBoundaryEventFollowsItsHostWithItsOwnPath() {
        val p = process("p",
            "userTasks" to listOf(task("approve"), task("escalate")),
            "events" to listOf(event("start", "startEvent"), event("end", "endEvent"), event("lost", "endEvent"),
                event("late", "boundaryEvent", "attachedTo" to "approve", "def" to "timer", "value" to "P3D", "cancelActivity" to "false")),
            "flows" to listOf(flow("start", "approve"), flow("approve", "end"), flow("late", "escalate"), flow("escalate", "lost")),
        )
        val all = steps(stories(p).single())
        assertEquals("start approve late(escalate lost) end", outline(all))
        val boundary = all.single { it["kind"] == "boundary" }
        assertEquals("timer", boundary["eventType"]); assertEquals("P3D", boundary["eventRef"])
        assertEquals("approve", boundary["attachedTo"]); assertEquals(false, boundary["interrupting"])
    }

    @Test
    fun aBoundaryPathThatGetsBackOntoTheLineStopsThere() {
        val p = process("p",
            "userTasks" to listOf(task("approve"), task("remind"), task("ship")),
            "events" to listOf(event("start", "startEvent"), event("end", "endEvent"),
                event("late", "boundaryEvent", "attachedTo" to "approve", "def" to "timer", "value" to "P1D")),
            "flows" to listOf(flow("start", "approve"), flow("approve", "ship"), flow("ship", "end"),
                flow("late", "remind"), flow("remind", "ship")),
        )
        val all = steps(stories(p).single())
        // "ship end" once, on the main line — not a second time under the timer
        assertEquals("start approve late(remind) ship end", outline(all))
        assertEquals("Ship", all.single { it["kind"] == "boundary" }["rejoins"])
    }

    @Test
    fun anActivityWithSeveralFlowsOutForksWithoutAGateway() {
        val p = process("p",
            "userTasks" to listOf(task("a"), task("b"), task("c")),
            "events" to listOf(event("start", "startEvent"), event("end1", "endEvent"), event("end2", "endEvent")),
            "flows" to listOf(flow("start", "a"), flow("a", "b"), flow("a", "c"), flow("b", "end1"), flow("c", "end2")),
        )
        val all = steps(stories(p).single())
        assertEquals("start a a[: b end1|: c end2]", outline(all))
        val fork = all.last()
        assertEquals("parallel", fork["gatewayKind"]); assertEquals(true, fork["implicit"])
    }

    @Test
    fun aSubProcessHoldsItsOwnFlowAndAnEventSubProcessFollowsTheMainOne() {
        val p = process("p",
            "userTasks" to listOf(task("inner") + ("parent" to "sub"), task("cleanup") + ("parent" to "onCancel")),
            "subProcesses" to listOf(
                mapOf("id" to "sub", "name" to "Sub", "type" to "subProcess", "eventSubProcess" to false),
                mapOf("id" to "onCancel", "name" to "On cancel", "type" to "subProcess", "eventSubProcess" to true)),
            "events" to listOf(event("start", "startEvent"), event("end", "endEvent"),
                event("subStart", "startEvent", "parent" to "sub"), event("subEnd", "endEvent", "parent" to "sub"),
                event("cancelled", "startEvent", "parent" to "onCancel", "def" to "signal", "value" to "cancel")),
            "flows" to listOf(flow("start", "sub"), flow("sub", "end"), flow("subStart", "inner"), flow("inner", "subEnd"),
                flow("cancelled", "cleanup")),
        )
        // A sub-process's start event is not a second start of the process, which is what it was while only the id told them apart.
        assertEquals("start sub(subStart inner subEnd) end onCancel(cancelled cleanup)", outline(steps(stories(p).single())))
    }

    @Test
    fun aCalledProcessIsOpenedOnceAndOnlyNamedBelowThat() {
        fun caller(key: String, calls: String) = process(key,
            "callActivities" to listOf(mapOf("id" to "call", "name" to "Call", "calledElement" to calls)),
            "events" to listOf(event("start", "startEvent"), event("end", "endEvent")),
            "flows" to listOf(flow("start", "call"), flow("call", "end")),
        )
        // a calls b, b calls a: opening every call would never end
        val a = stories(caller("a", "b"), caller("b", "a")).first { it["key"] == "a" }
        assertEquals("start call(start call end) end", outline(steps(a)))
        val outer = steps(a)[1]
        assertEquals("B", outer["targetName"]); assertNull(outer["notOpened"])
        val inner = Dyn.map(Dyn.list(outer["steps"])[1])
        assertEquals("nested", inner["notOpened"]); assertNull(inner["steps"])
    }

    @Test
    fun aCallThatCannotBeResolvedSaysWhy() {
        fun calling(element: String?, type: String? = null) = steps(stories(process("p",
            "callActivities" to listOf(mapOf("id" to "call", "calledElement" to element, "calledElementType" to type)),
            "events" to listOf(event("start", "startEvent")),
            "flows" to listOf(flow("start", "call")),
        ), process("known")).first { it["key"] == "p" })[1]["notOpened"]
        assertEquals("dynamic", calling("\${target}"))
        assertEquals("not in this project", calling("elsewhere"))
        // an id is a deployed definition's, so a model that happens to carry it as its key is not the target
        assertEquals("not in this project", calling("known", type = "id"))
        assertEquals("no target", calling(null))
    }

    @Test
    fun aProcessWhoseBranchesNeverMeetIsCutOffAndSaysSo() {
        // Each gateway's branches reach the next gateway by different end events' worth of structure:
        // two tasks that both lead on, with a third way out to an end — so no node is common to all three.
        val n = 14
        val tasks = ArrayList<Map<String, Any?>>(); val gws = ArrayList<Map<String, Any?>>(); val ends = ArrayList<Map<String, Any?>>()
        val flows = arrayListOf(flow("start", "g0"))
        for (i in 0 until n) {
            gws.add(gateway("g$i")); tasks.add(task("l$i")); tasks.add(task("r$i")); ends.add(event("e$i", "endEvent"))
            flows.addAll(listOf(flow("g$i", "l$i"), flow("g$i", "r$i"), flow("g$i", "e$i"),
                flow("l$i", "g${i + 1}"), flow("r$i", "g${i + 1}")))
        }
        ends.add(event("g$n", "endEvent"))
        val story = stories(process("p", "userTasks" to tasks, "gateways" to gws,
            "events" to listOf(event("start", "startEvent")) + ends, "flows" to flows)).single()
        assertEquals(true, story["truncated"])
        assertTrue(Dyn.list(story["warnings"]).single().toString().startsWith("stopped after ${FlowStories.MAX_STEPS} steps"))
    }

    private val case = mapOf(
        "key" to "c", "name" to "Review", "file" to "c.cmmn",
        "planModel" to mapOf("id" to "plan", "name" to "Plan", "type" to "casePlanModel",
            "children" to listOf(
                mapOf("id" to "escalate", "planItemId" to "piEscalate", "name" to "Escalate", "type" to "humanTask",
                    "rules" to mapOf("manualActivationRule" to true)),
                mapOf("id" to "review", "planItemId" to "piReview", "name" to "Review", "type" to "humanTask",
                    "candidateGroups" to "backoffice, audit", "rules" to emptyMap<String, Any?>()),
                mapOf("id" to "fulfil", "planItemId" to "piFulfil", "name" to "Fulfil", "type" to "processTask",
                    "processRef" to "p", "rules" to emptyMap<String, Any?>()),
            ),
            "criteria" to listOf(
                mapOf("id" to "cr1", "planItemDef" to "escalate", "planItemId" to "piEscalate", "type" to "entryCriterion", "sentryRef" to "s1"),
                mapOf("id" to "cr2", "planItemDef" to "review", "planItemId" to "piReview", "type" to "exitCriterion", "sentryRef" to "s2"),
            )),
        "sentries" to listOf(
            mapOf("id" to "s1", "condition" to "\${overdue}", "onParts" to listOf("piReview"),
                "events" to listOf(mapOf("source" to "piReview", "event" to "complete"))),
            mapOf("id" to "s2", "condition" to "\${withdrawn}", "onParts" to emptyList<String>()),
        ),
    )

    @Test
    fun aPlanItemFollowsWhatItWaitsForAndSaysItInTheReportsWords() {
        val p = process("p", "events" to listOf(event("start", "startEvent"), event("end", "endEvent")),
            "flows" to listOf(flow("start", "end")))
        val story = stories(p, cases = listOf(case)).single { it["kind"] == "case" }
        // "Escalate" is first in the model and waits for "Review": it reads right after it, and the
        // rest keeps the model's order
        assertEquals("review escalate fulfil(start end)", outline(steps(story)))
        val (review, escalate, fulfil) = steps(story)
        assertEquals(listOf("on complete \"Review\" if `\${overdue}`"), escalate["entry"])
        assertEquals(true, escalate["manualActivation"])
        assertEquals(listOf("if `\${withdrawn}`"), review["exit"]); assertNull(review["entry"])
        assertEquals(listOf("backoffice", "audit"), review["candidateGroups"])
        assertEquals("call", fulfil["kind"]); assertEquals("process", fulfil["targetKind"])

        // …and it is the plan tree's wording, so the page and the Markdown cannot disagree about a sentry
        val report = OverviewRenderer.render(mapOf("stats" to mapOf("models" to 1), "cases" to listOf(case)), File("proj"))
        assertTrue(report, report.contains("entry on complete \"Review\" if `\${overdue}`"))
    }

    @Test
    fun aModelSaysWhoStartsItOnlyWhereItNamesThem() {
        val result = mapOf("processes" to listOf(process("open"), process("gated")),
            "access" to listOf(mapOf("model" to "gated", "modelType" to "process", "action" to "start",
                "groups" to listOf("sales"), "users" to emptyList<String>())))
        val byKey = FlowStories.build(result).associateBy { it["key"] }
        assertEquals(mapOf("groups" to listOf("sales"), "users" to emptyList<String>()), byKey.getValue("gated")["startedBy"])
        // A process nobody is named for still has a story: leaving it out made a project without starter groups an empty page.
        assertFalse(byKey.getValue("open").containsKey("startedBy"))
        assertEquals(listOf("process open has no start event"), byKey.getValue("open")["warnings"])
    }

    @Test
    fun flowJsonMatchesGolden() {
        val fixture = File(javaClass.classLoader.getResource("miniproject")!!.toURI())
        GoldenFiles.assertMatches("miniproject.flow.json", FlowStories.render(Atlas.extract(fixture), fixture) + "\n")
    }
}
