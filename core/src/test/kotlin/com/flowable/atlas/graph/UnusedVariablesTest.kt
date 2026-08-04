package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The silence rules of [UnusedVariables], one test each.
 *
 * These matter more than the verdict itself. Saying "written but never read" about a variable something
 * *does* read is the one failure that would make the whole report untrustworthy, and every rule here
 * covers a shape that really occurs — a name the binding harvester drops, a bean that reads the whole
 * scope, a callee outside the project. Hand-built nodes rather than a fixture, so each rule can be
 * isolated to exactly the one fact that triggers it.
 */
class UnusedVariablesTest {

    /** A `variable` node with the given write/read sites, ready to be judged. */
    private fun variable(
        name: String,
        writes: List<Map<String, Any?>> = emptyList(),
        reads: List<Map<String, Any?>> = emptyList(),
    ): MutableMap<String, Any?> = linkedMapOf(
        "id" to "variable:$name", "key" to name, "type" to "variable",
        "data" to linkedMapOf<String, Any?>(
            "writes" to writes, "reads" to reads,
            "writeCount" to writes.size, "readCount" to reads.size,
        ),
    )

    private fun write(model: String, via: String = "scriptApi", scope: String? = null, unresolved: Boolean = false) =
        linkedMapOf<String, Any?>("model" to model, "via" to via).also {
            if (scope != null) it["scope"] = scope
            if (unresolved) it["scopeUnresolved"] = true
        }

    private fun read(model: String, via: String = "expression") =
        mapOf<String, Any?>("model" to model, "via" to via)

    private fun model(id: String, type: String = "process") =
        linkedMapOf<String, Any?>("id" to id, "key" to id.substringAfter(':'), "type" to type)

    private fun edge(s: String, t: String, rel: String = "calls") = mapOf<String, Any?>("s" to s, "t" to t, "rel" to rel)

    /** Judge [vars] against [nodes]/[edges] and return each variable's verdict marks. */
    private fun judge(
        vars: List<MutableMap<String, Any?>>,
        others: List<MutableMap<String, Any?>> = emptyList(),
        edges: List<Map<String, Any?>> = emptyList(),
        readsUnknown: Set<String> = emptySet(),
        readsEverything: Set<String> = emptySet(),
        mustacheIgnored: Set<String> = setOf("total", "data", "payload"),
    ): Map<String, Map<String, Any?>> {
        val all = (vars + others).associateByTo(LinkedHashMap()) { it["id"].toString() }
        UnusedVariables.decide(all, edges, readsUnknown, readsEverything, mustacheIgnored)
        @Suppress("UNCHECKED_CAST")
        return vars.associate {
            it["key"].toString() to (it["data"] as Map<String, Any?>)
                .filterKeys { k -> k in setOf("unread", "unreadIn", "readsUnknown") }
        }
    }

    @Test
    fun aWriteWithNoReadAnywhereIsReported() {
        val v = judge(listOf(variable("stamp", writes = listOf(write("process:p")))))
        assertEquals(mapOf("unread" to true), v["stamp"])
    }

    @Test
    fun aVariableWithAnyReadIsNotReported() {
        val v = judge(listOf(variable("stamp", writes = listOf(write("process:p")), reads = listOf(read("process:p")))))
        assertEquals(emptyMap<String, Any?>(), v["stamp"])
    }

    @Test
    fun aVariableNothingWritesIsNotOurBusiness() {
        // Read but never written is the mirror question, and a different one: most such variables arrive
        // legitimately from outside (a start payload, a REST call, the Work UI).
        val v = judge(listOf(variable("fromOutside", reads = listOf(read("process:p")))))
        assertEquals(emptyMap<String, Any?>(), v["fromOutside"])
    }

    @Test
    fun aSuspectedReadStillCounts() {
        // A bare identifier in a script body is a guess, not a fact — but it is evidence *for* use, and
        // this check only ever speaks when there is none at all.
        val v = judge(listOf(variable(
            "stamp",
            writes = listOf(write("process:p")),
            reads = listOf(mapOf("model" to "process:p", "via" to "scriptRead", "guess" to true)),
        )))
        assertEquals(emptyMap<String, Any?>(), v["stamp"])
    }

    @Test
    fun silenceRuleUndecidableConstruct() {
        val v = judge(listOf(variable("watched", writes = listOf(write("process:p")))), readsUnknown = setOf("watched"))
        assertEquals(mapOf("readsUnknown" to true), v["watched"])
    }

    @Test
    fun silenceRuleNameTheBindingHarvesterIgnores() {
        // `{{total}}` is deliberately dropped as a binding root, so Atlas never collected the frontend
        // reads of `total` — and must not then claim there are none.
        val v = judge(listOf(variable("total", writes = listOf(write("process:p")))))
        assertEquals(mapOf("readsUnknown" to true), v["total"])
        // …including the `$`-prefixed form the harvester also strips.
        val d = judge(listOf(variable("\$data", writes = listOf(write("process:p")))))
        assertEquals(mapOf("readsUnknown" to true), d["\$data"])
    }

    @Test
    fun silenceRuleNamePassedAsAStringLiteral() {
        // `${myLib.get('orderId')}` addresses a variable by name through a function Atlas cannot follow.
        // The literal's mere existence is enough to stop it concluding anything.
        val v = judge(
            listOf(variable("orderId", writes = listOf(write("process:p")))),
            others = listOf(linkedMapOf("id" to "string:orderId", "key" to "orderId", "type" to "string")),
        )
        assertEquals(mapOf("readsUnknown" to true), v["orderId"])
    }

    @Test
    fun silenceRuleAScopeThatReadsEveryVariable() {
        // Directly: the writing model's own script does `execution.getVariables()`.
        val direct = judge(
            listOf(variable("x", writes = listOf(write("process:p")))),
            readsEverything = setOf("process:p"),
        )
        assertEquals(mapOf("readsUnknown" to true), direct["x"])

        // Indirectly: the model hands execution to a bean that does. The bean sees every variable the
        // model has, so nothing the model writes can be proven unread.
        val viaBean = judge(
            listOf(variable("y", writes = listOf(write("process:p")))),
            others = listOf(model("java:com.example.Nosy", "java")),
            edges = listOf(edge("process:p", "java:com.example.Nosy", "serviceTask-delegate")),
            readsEverything = setOf("java:com.example.Nosy"),
        )
        assertEquals(mapOf("readsUnknown" to true), viaBean["y"])
    }

    @Test
    fun silenceRuleACalleeOutsideTheProject() {
        // `<flowable:in target="subOrderId"/>` into a process that is not in this repository. Nothing can
        // be concluded about a model that is not there — dropping the scope silently would turn "we
        // cannot see the called model" into "nobody reads it".
        val v = judge(listOf(variable("subOrderId", writes = listOf(write("process:p", "inParameter", unresolved = true)))))
        assertEquals(mapOf("readsUnknown" to true), v["subOrderId"])
    }

    @Test
    fun anInputMappedIntoACalleeThatNeverReadsItIsReportedAgainstThatCallee() {
        // The case a caller passing a parameter nobody consumes produces: `orderId` is read in the caller
        // (so it is not globally unread) but the called process never looks at `subOrderId`.
        val v = judge(
            listOf(variable(
                "subOrderId",
                writes = listOf(write("process:caller", "inParameter", scope = "process:callee")),
                reads = listOf(read("process:caller")),
            )),
            others = listOf(model("process:caller"), model("process:callee")),
        )
        assertEquals(mapOf("unreadIn" to listOf("process:callee")), v["subOrderId"])
    }

    @Test
    fun aCalleeThatDoesReadItIsNotReported() {
        val v = judge(
            listOf(variable(
                "subOrderId",
                writes = listOf(write("process:caller", "inParameter", scope = "process:callee")),
                reads = listOf(read("process:callee")),
            )),
            others = listOf(model("process:caller"), model("process:callee")),
        )
        assertEquals(emptyMap<String, Any?>(), v["subOrderId"])
    }

    @Test
    fun aReadTwoHopsInsideTheCalleeStillCounts() {
        // The callee opens a user-task form which is the only thing that reads the value. Checking only
        // the callee itself would report a mapping that is doing exactly its job.
        val v = judge(
            listOf(variable(
                "subOrderId",
                writes = listOf(write("process:caller", "inParameter", scope = "process:callee")),
                reads = listOf(read("form:deep")),
            )),
            others = listOf(
                model("process:caller"), model("process:callee"),
                model("form:middle", "form"), model("form:deep", "form"),
            ),
            edges = listOf(
                edge("process:callee", "form:middle", "userTask-form"),
                edge("form:middle", "form:deep", "subform"),
            ),
        )
        assertEquals(emptyMap<String, Any?>(), v["subOrderId"])

        // …but three hops out is beyond what this check claims to know, so it reports rather than
        // pretending to have searched the whole project.
        val far = judge(
            listOf(variable(
                "subOrderId",
                writes = listOf(write("process:caller", "inParameter", scope = "process:callee")),
                reads = listOf(read("form:far")),
            )),
            others = listOf(
                model("process:caller"), model("process:callee"),
                model("form:a", "form"), model("form:b", "form"), model("form:far", "form"),
            ),
            edges = listOf(
                edge("process:callee", "form:a", "userTask-form"),
                edge("form:a", "form:b", "subform"),
                edge("form:b", "form:far", "subform"),
            ),
        )
        assertEquals(mapOf("unreadIn" to listOf("process:callee")), far["subOrderId"])
    }

    @Test
    fun appMembershipIsNotAReachablePath() {
        // Every model of an app is one `contains` edge from every other. Following those would make any
        // read anywhere in the app count as a read in the callee, which would silence the check entirely.
        val v = judge(
            listOf(variable(
                "subOrderId",
                writes = listOf(write("process:caller", "inParameter", scope = "process:callee")),
                reads = listOf(read("process:unrelated")),
            )),
            others = listOf(
                model("process:caller"), model("process:callee"), model("process:unrelated"),
                model("app:a", "app"),
            ),
            edges = listOf(
                edge("app:a", "process:callee", "contains"),
                edge("app:a", "process:unrelated", "contains"),
            ),
        )
        assertEquals(mapOf("unreadIn" to listOf("process:callee")), v["subOrderId"])
    }

    @Test
    fun theTwoVerdictsAreNeverBothStamped() {
        // They answer different questions with different remedies — delete the variable vs. delete the
        // mapping — so a variable that has no reader at all is reported once, as the stronger of the two.
        val v = judge(
            listOf(variable("x", writes = listOf(write("process:caller", "inParameter", scope = "process:callee")))),
            others = listOf(model("process:caller"), model("process:callee")),
        )
        assertEquals(mapOf("unread" to true), v["x"])
        assertNull(v["x"]!!["unreadIn"])
    }

    @Test
    fun theSilenceRulesAreDocumentedForTheReport() {
        // The page states what Atlas cannot see; if a rule is added without a sentence for it, the report
        // silently overstates its own confidence.
        assertEquals(8, UnusedVariables.SILENCE_RULES.size)
        assertTrue(UnusedVariables.SILENCE_RULES.all { it.isNotBlank() })
    }
}
