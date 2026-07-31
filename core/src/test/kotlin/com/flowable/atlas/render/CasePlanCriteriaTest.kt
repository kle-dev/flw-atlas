package com.flowable.atlas.render

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
        val sentryLine = render(case).single { it.contains("Sentry `s2`") }
        assertTrue(sentryLine, sentryLine.contains("on complete reviewTask"))
        assertTrue(sentryLine, !sentryLine.contains("['"))
    }
}
