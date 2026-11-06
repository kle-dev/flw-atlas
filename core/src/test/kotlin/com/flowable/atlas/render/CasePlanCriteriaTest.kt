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
                mapOf("id" to "reviewTask", "name" to "Review", "type" to "humanTask", "rules" to emptyMap<String, Any?>()),
                mapOf("id" to "escalate", "name" to "Escalate", "type" to "humanTask", "rules" to emptyMap<String, Any?>()),
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
            mapOf("id" to "s2", "condition" to null, "onParts" to listOf("complete reviewTask")),
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private val plan0 = case["planModel"] as Map<String, Any?>

    @Test
    fun entryCriterionShowsItsSentryCondition() {
        val line = render(case).single { it.contains("Escalate") }
        assertTrue(line, line.contains("entry if `\${overdue}`"))
    }

    @Test
    fun exitCriterionWithoutAConditionShowsItsOnParts() {
        val line = render(case).single { it.contains("Review") && it.contains("Human task") }
        assertTrue(line, line.contains("exit on complete reviewTask"))
    }

    @Test
    fun sentryOnPartsAreNotPrintedAsAPythonList() {
        val line = render(case).single { it.contains("Review") && it.contains("Human task") }
        assertTrue(line, line.contains("exit on complete reviewTask"))
        assertTrue(line, !line.contains("['"))
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
                mapOf("id" to "s3", "condition" to "\${\n  ready\n}", "onParts" to listOf("complete escalate")),
                mapOf("id" to "s4", "condition" to "\${closed}", "onParts" to emptyList<String>()),
                mapOf("id" to "orphan", "condition" to "\${never}", "onParts" to emptyList<String>()),
            ),
        )
        val lines = render(both)
        val review = lines.single { it.contains("Review") && it.contains("Human task") }
        // the event and the condition, the multi-line condition on one line
        assertTrue(review, review.contains("entry on complete escalate if `\${ ready }`"))
        assertEquals("said once", 1, lines.count { it.contains("ready }") })
        // the case's own exit criterion sits on the plan model's line
        val plan = lines.single { it.contains("Case plan model") }
        assertTrue(plan, plan.startsWith("- 📁") && plan.contains("exit if `\${closed}`"))
        // only the sentry nothing uses is listed after the tree
        val listed = lines.dropWhile { !it.contains("Sentries no plan item uses") }
        assertTrue(lines.joinToString("\n"), listed.size >= 2 && listed[1].contains("`orphan` if `\${never}`"))
        assertTrue(lines.none { it.contains("`s1`") || it.contains("`s3`") || it.contains("`s4`") })
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
