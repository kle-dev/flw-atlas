package com.flowable.atlas.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A CMMN plan item's entry/exit criteria must be shown **with the condition behind them**.
 *
 * A case has no sequence flows — criteria are what makes a plan item start or stop — but the condition
 * lives in a separate `sentries` list keyed by `sentryRef`. The report listed the plan tree and the
 * sentries apart, leaving the reader to join them by id, which is precisely the work a generated report
 * exists to have already done. The `miniproject` fixture has no criteria, hence this test.
 */
class CasePlanCriteriaTest {

    private fun render(case: Map<String, Any?>): List<String> = OverviewRenderer.render(
        mapOf("stats" to mapOf("models" to 1), "cases" to listOf(case)),
        File("proj"),
    ).lines()

    private val case = mapOf(
        "key" to "c", "name" to "Review", "file" to "c.cmmn",
        "planModel" to mapOf(
            "id" to "planModel", "name" to "Plan", "type" to "casePlanModel",
            "children" to listOf(
                mapOf("id" to "reviewTask", "planItemId" to "planItemReviewTask", "name" to "Review",
                    "type" to "humanTask", "rules" to emptyMap<String, Any?>()),
                mapOf("id" to "escalate", "planItemId" to "planItemEscalate", "name" to "Escalate",
                    "type" to "humanTask", "rules" to emptyMap<String, Any?>()),
            ),
            "criteria" to listOf(
                mapOf("id" to "cr1", "planItem" to "Escalate", "planItemDef" to "escalate",
                    "type" to "entryCriterion", "sentryRef" to "s1"),
                mapOf("id" to "cr2", "planItem" to "Review", "planItemDef" to "reviewTask",
                    "type" to "exitCriterion", "sentryRef" to "s2"),
            ),
        ),
        "sentries" to listOf(
            mapOf("id" to "s1", "condition" to "\${overdue}", "onParts" to emptyList<String>()),
            mapOf("id" to "s2", "condition" to null, "onParts" to listOf("planItemEscalate"),
                "events" to listOf(mapOf("source" to "planItemEscalate", "event" to "complete"))),
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private val plan0 = case["planModel"] as Map<String, Any?>

    @Test
    fun entryCriterionShowsItsSentryCondition() {
        val line = render(case).single { it.contains("Human task Escalate") }
        assertTrue(line, line.contains("entry if `\${overdue}`"))
    }

    /**
     * An on-part holds a plan item's id, and the transition it waits for sits beside it in `events`. The
     * tree printed the id as stored — `exit on planItemEscalate`, an id none of its lines carries — and
     * dropped the transition. This test used to feed the renderer on-parts shaped `"complete reviewTask"`,
     * which no parser writes, so it passed while every real case read that way.
     */
    @Test
    fun anOnPartIsTheTransitionAndThePlanItemByName() {
        val line = render(case).single { it.contains("Human task Review") }
        assertTrue(line, line.endsWith("· exit on complete \"Escalate\""))
        assertTrue(line, !line.contains("planItemEscalate") && !line.contains("['"))
    }

    @Test
    fun aNameThatRepeatsIsQualifiedByItsStageOrByItsIdBesideATwin() {
        fun item(id: String, name: String?, type: String = "humanTask") = mapOf(
            "id" to id, "planItemId" to "pi_$id", "name" to name, "type" to type, "rules" to emptyMap<String, Any?>(),
        )
        fun waitsFor(n: Int, source: String, event: String = "complete") = mapOf(
            "id" to "s$n", "condition" to null, "onParts" to listOf(source),
            "events" to listOf(mapOf("source" to source, "event" to event)),
        )
        fun entry(n: Int, of: String) = mapOf(
            "id" to "cr$n", "planItemDef" to of, "planItemId" to "pi_$of", "type" to "entryCriterion", "sentryRef" to "s$n",
        )
        val intake = item("intake", "Intake", "stage") + mapOf(
            "children" to listOf(item("audit1", "Audit"), item("skipA", "Skip"), item("skipB", "Skip"), item("timer", null)),
            "criteria" to emptyList<Any?>(),
        )
        val twins = case + mapOf(
            "planModel" to mapOf(
                "id" to "planModel", "name" to "Plan", "type" to "casePlanModel",
                "children" to listOf(intake, item("audit2", "Audit"), item("a", "A"), item("b", "B"), item("c", "C"),
                    item("d", "D"), item("e", "E")),
                "criteria" to listOf(entry(1, "a"), entry(2, "b"), entry(3, "c"), entry(4, "d"), entry(5, "e")),
            ),
            "sentries" to listOf(
                waitsFor(1, "pi_audit1"), waitsFor(2, "pi_audit2"), waitsFor(3, "pi_skipB", "occur"),
                waitsFor(4, "pi_timer", "occur"), waitsFor(5, "pi_gone"),
            ),
        )
        val lines = render(twins)
        fun of(name: String) = lines.single { it.trim().startsWith("- Human task $name ·") }.substringAfter(" · ")
        // one name in two stages: said with where it is
        assertEquals("entry on complete \"Audit\" in \"Intake\"", of("A"))
        assertEquals("entry on complete \"Audit\" on the case plan", of("B"))
        // one name twice in one stage: only its id tells them apart, so the line of the one named carries it too
        assertEquals("entry on occur \"Skip\" (`pi_skipB`)", of("C"))
        assertEquals(listOf("- Human task Skip", "- Human task Skip (`pi_skipB`)"),
            lines.filter { it.contains("Human task Skip") }.map { it.trim() })
        // no name: the id its own line shows; no such plan item: the id as the model has it
        assertEquals("entry on occur `timer`", of("D"))
        assertEquals("entry on complete `pi_gone`", of("E"))
    }

    /**
     * A sentry with neither an event nor a condition is satisfied as soon as the engine evaluates it. It
     * printed as a bare `entry`, which reads as a criterion whose condition got lost.
     */
    @Test
    fun anEmptySentrySaysThatItOpensAtOnce() {
        val empty = case + mapOf("sentries" to listOf(
            mapOf("id" to "s1", "condition" to null, "onParts" to emptyList<String>(), "triggerMode" to "onEvent"),
            mapOf("id" to "s2", "condition" to " ", "onParts" to emptyList<String>()),
        ))
        val lines = render(empty)
        assertTrue(lines.single { it.contains("Human task Escalate") }.endsWith("· entry at once (its sentry has no event and no condition)"))
        assertTrue(lines.single { it.contains("Human task Review") }.endsWith("· exit at once (its sentry has no event and no condition)"))
    }

    /**
     * `onEvent` forgets a satisfied part, so it matters exactly where two things have to meet. And an exit
     * that completes its stage fires the sentries waiting for that stage to complete; a plain one does not.
     *
     * The line also says how its parts combine, because nothing else does for a reader who greps it: the
     * on-parts of one sentry all have to happen (*and*), several criteria of one kind are alternatives
     * (*or*).
     */
    @Test
    fun theTriggerModeWhatAnExitDoesAndHowCriteriaCombineAreSaidOnTheLine() {
        fun sentry(id: String, condition: String?, vararg on: String) = mapOf(
            "id" to id, "condition" to condition, "onParts" to on.toList(), "triggerMode" to "onEvent",
            "events" to on.map { mapOf("source" to it, "event" to "complete") },
        )
        val modes = case + mapOf(
            "planModel" to plan0 + mapOf("criteria" to listOf(
                mapOf("id" to "cr1", "planItemDef" to "escalate", "type" to "entryCriterion", "sentryRef" to "s1"),
                mapOf("id" to "cr2", "planItemDef" to "escalate", "type" to "entryCriterion", "sentryRef" to "s2"),
                mapOf("id" to "cr3", "planItemDef" to "escalate", "type" to "entryCriterion", "sentryRef" to "s3"),
                mapOf("id" to "cr4", "planItemDef" to "reviewTask", "type" to "exitCriterion", "sentryRef" to "s4",
                    "exitEventType" to "forceComplete"),
                mapOf("id" to "cr5", "planItemDef" to "reviewTask", "type" to "exitCriterion", "sentryRef" to "s5"),
            )),
            "sentries" to listOf(
                sentry("s1", "\${overdue}", "planItemReviewTask"), sentry("s2", null, "planItemReviewTask"),
                sentry("s3", "\${overdue}"), sentry("s4", null, "planItemEscalate"),
                sentry("s5", null, "planItemEscalate", "planItemReviewTask"),
            ),
        )
        val lines = render(modes)
        assertTrue(lines.single { it.contains("Human task Escalate") }.endsWith(
            "Escalate · entry on complete \"Review\" if `\${overdue}` (triggerMode=onEvent) · or entry on complete \"Review\" · or entry if `\${overdue}`"))
        assertTrue(lines.single { it.contains("Human task Review") }.endsWith(
            "Review · exit on complete \"Escalate\" (exitEventType=forceComplete) · " +
                "or exit on complete \"Escalate\" and complete \"Review\" (triggerMode=onEvent)"))
    }

    /**
     * What a case hands to the process or case it starts, and takes back, on the plan item's line as on a
     * call activity's. A literal `sourceExpression` is quoted: bare, `express→priority` reads as a
     * variable called `express` being copied.
     */
    @Test
    fun aProcessTaskCarriesItsInAndOutMappings() {
        fun io(dir: String, source: String?, target: String, expression: Boolean = false, kind: String = dir) = mapOf(
            "element" to "startReview", "dir" to dir, "kind" to kind, "source" to source, "target" to target,
        ) + (if (expression) mapOf("expression" to true) else emptyMap())
        val mapped = case + mapOf(
            "planModel" to plan0 + mapOf("children" to listOf(mapOf(
                "id" to "startReview", "name" to "Start review", "type" to "processTask",
                "processRef" to "DEMO-review", "rules" to mapOf("requiredRule" to true),
            )), "criteria" to emptyList<Any?>()),
            "ioParameters" to listOf(
                io("in", "orderId", "reviewedOrder"), io("in", "express", "priority", expression = true),
                io("in", "\${root.id}", "caseId", expression = true), io("in", null, "reviewTask"),
                io("out", "approved", "approved"),
                // other kinds of parameter stay off the line, as they do on a process's
                io("in", "x", "y", kind = "eventInParameter"),
            ),
        )
        val line = render(mapped).single { it.contains("Start review") }
        assertTrue(line, line.endsWith("processRef=`DEMO-review` in(orderId→reviewedOrder) in(\"express\"→priority) " +
            "in(`\${root.id}`→caseId) in(→reviewTask) out(approved→approved) · required"))
    }

    /**
     * Every sentry was listed again after the tree — 42% of one real report's case section — though the
     * tree already carried all but two of them. Now the list holds only what no criterion reaches, and a
     * sentry's line says everything it waits for: its events *and* its condition.
     */
    @Test
    fun aSentryIsSpelledOutOnceWhereItIsUsedAndTheUnusedOnesAreListed() {
        val both = case + mapOf(
            "planModel" to plan0 + mapOf(
                "exitCriteria" to listOf(mapOf("id" to "crCase", "type" to "exitCriterion", "sentryRef" to "s4")),
                "criteria" to plan0["criteria"] as List<*> + listOf(
                    mapOf("id" to "cr3", "planItemDef" to "reviewTask", "type" to "entryCriterion", "sentryRef" to "s3"),
                ),
            ),
            "sentries" to (case["sentries"] as List<*>) + listOf(
                mapOf("id" to "s3", "condition" to "\${\n  ready\n}", "onParts" to listOf("planItemEscalate"),
                    "events" to listOf(mapOf("source" to "planItemEscalate", "event" to "complete"))),
                mapOf("id" to "s4", "condition" to "\${closed}", "onParts" to emptyList<String>()),
                mapOf("id" to "orphan", "condition" to "\${never}", "onParts" to emptyList<String>()),
            ),
        )
        val lines = render(both)
        val review = lines.single { it.contains("Human task Review") }
        // the event and the condition, the multi-line condition on one line
        assertTrue(review, review.contains("entry on complete \"Escalate\" if `\${ ready }`"))
        assertEquals("said once", 1, lines.count { it.contains("ready }") })
        // the case's own exit criterion sits on the plan model's line
        val plan = lines.single { it.contains("Case plan model") }
        assertTrue(plan, plan.startsWith("- 📁") && plan.contains("exit if `\${closed}`"))
        // only the sentry nothing uses is listed after the tree
        val listed = lines.dropWhile { !it.contains("Sentries no plan item uses") }
        assertTrue(lines.joinToString("\n"), listed.size >= 2 && listed[1].contains("`orphan` if `\${never}`"))
        assertTrue(lines.none { it.contains("`s1`") || it.contains("`s3`") || it.contains("`s4`") })
    }

    /**
     * An Init-Variables task's line ended at its delegate expression, the same one on every such task, so
     * the tree named the task and withheld the one thing it does. Each assignment says its value by the
     * type the model gives it.
     */
    @Test
    fun anInitVariablesTaskSaysWhatItAssigns() {
        fun set(target: String, source: String?, type: String?, vararg more: Pair<String, Any?>) = mapOf(
            "element" to "init", "dir" to "in", "kind" to "variableMapping", "source" to source, "target" to target,
            "type" to type,
        ) + more
        val init = case + mapOf(
            "planModel" to plan0 + mapOf("children" to listOf(mapOf(
                "id" to "init", "name" to "Initialize", "type" to "task", "delegateExpression" to "\${demoInit}",
                "rules" to emptyMap<String, Any?>(),
            )), "criteria" to emptyList<Any?>()),
            "ioParameters" to listOf(
                set("approved", "false", "boolean"), set("cycles", "1", "long"), set("stage", "Intake", "string"),
                set("owner", "initiator", "variable"), set("due", "\${now}", null, "expression" to true),
                set("draft", null, "jsonObject"), set("name", "initiator", "variable", "container" to "contact"),
            ),
        )
        val line = render(init).single { it.contains("Initialize") }
        assertTrue(line, line.endsWith("delegateExpression=`\${demoInit}` set(approved=false) set(cycles=1) " +
            "set(stage=\"Intake\") set(owner=`initiator`) set(due=`\${now}`) set(draft: jsonObject) " +
            "set(contact.name=`initiator`)"))
    }

    /** Under a `###` heading the tree used to start indented by four spaces — a code block in any reader. */
    @Test
    fun thePlanTreeIsAListNotACodeBlock() {
        val lines = render(case)
        val root = lines.indexOfFirst { it.contains("Case plan model") }
        assertTrue(lines[root], lines[root].startsWith("- 📁"))
        assertTrue(lines[root + 1], lines[root + 1].startsWith("    - "))
    }

    @Test
    fun aRuleReadsAsWhatItMeansNotAsAPythonBoolean() {
        val ruled = case + mapOf("planModel" to plan0 + mapOf(
            "children" to listOf(
                mapOf("id" to "a", "name" to "Always", "type" to "humanTask",
                    "rules" to mapOf("requiredRule" to true, "manualActivationRule" to true)),
                mapOf("id" to "b", "name" to "Again", "type" to "humanTask",
                    "rules" to mapOf("repetitionRule" to "\${more}", "maxInstanceCount" to "2")),
            ),
        ))
        val lines = render(ruled)
        assertTrue(lines.single { it.contains("Always") }.endsWith("Always · required · manual activation"))
        assertTrue(lines.single { it.contains("Again") }.endsWith("Again · repeats while `\${more}` · maxInstanceCount=2"))
        assertTrue(lines.none { it.contains("True") || it.contains("Rule=") })
    }
}
