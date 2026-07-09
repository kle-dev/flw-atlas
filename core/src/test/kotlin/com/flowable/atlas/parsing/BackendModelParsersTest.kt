package com.flowable.atlas.parsing

import com.flowable.atlas.graph.Ctx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ports the BPMN/CMMN assertions of Python's `tests/test_parsers.py`
 * (`test_parse_bpmn_tasks_and_refs`, `test_parse_cmmn_plan_model`) to the Kotlin
 * [BackendModelParsers], run over the same `miniproject` fixtures the golden harness uses.
 */
class BackendModelParsersTest {

    private fun bytes(path: String): ByteArray =
        File(javaClass.classLoader.getResource(path)!!.toURI()).readBytes()

    /** `{(rel, kind, value)}` of the static refs recorded on ctx — mirrors Python's `_refs`. */
    private fun refs(ctx: Ctx): Set<Triple<Any?, Any?, Any?>> =
        ctx.refs.map { Triple(it["rel"], it["kind"], it["value"]) }.toSet()

    @Suppress("UNCHECKED_CAST")
    private fun listOfMaps(v: Any?): List<Map<String, Any?>> = (v as List<Map<String, Any?>>)

    @Test
    fun parseBpmnTasksAndRefs() {
        val ctx = Ctx()
        val procs = BackendModelParsers.parseBpmn(bytes("miniproject/processes/order.bpmn"), ctx, "processes/order.bpmn")

        // one process, keyed by its id
        assertEquals(listOf("orderProcess"), procs.map { it["key"] })
        val p = procs[0]
        assertEquals("Order Process", p["name"])
        assertEquals("Handles an order from entry to approval.", p["documentation"])
        assertEquals("sales", p["candidateStarterGroups"])

        // user task, its form key and assignment
        val userTasks = listOfMaps(p["userTasks"])
        assertEquals(listOf("approveTask"), userTasks.map { it["id"] })
        assertEquals("orderForm", userTasks[0]["formKey"])
        assertEquals("backoffice", userTasks[0]["candidateGroups"])

        // service tasks (document order); decideTask is a dmn-typed serviceTask
        val serviceTasks = listOfMaps(p["serviceTasks"])
        assertEquals(listOf("calcTask", "notifyTask", "decideTask"), serviceTasks.map { it["id"] })
        assertEquals("\${demoBean.run(execution)}", serviceTasks[0]["expression"])
        assertEquals("total", serviceTasks[0]["resultVariable"])
        assertEquals("\${notifierBean}", serviceTasks[1]["delegateExpression"])
        assertEquals("dmn", serviceTasks[2]["type"])

        // rule task surfaced from the dmn service task's field injection
        val ruleTasks = listOfMaps(p["ruleTasks"])
        assertEquals(listOf("decideTask"), ruleTasks.map { it["id"] })
        assertEquals("orderDecision", ruleTasks[0]["decisionRef"])

        // call activity + called element
        val callActivities = listOfMaps(p["callActivities"])
        assertEquals(listOf("callSub"), callActivities.map { it["id"] })
        assertEquals("fulfilmentProcess", callActivities[0]["calledElement"])

        // events (start/end), receive task lands in otherTasks
        assertEquals(listOf("start", "end"), listOfMaps(p["events"]).map { it["id"] })
        assertEquals(listOf("waitShipped"), listOfMaps(p["otherTasks"]).map { it["id"] })

        // sequence-flow conditions
        val conditions = listOfMaps(p["conditions"])
        assertEquals(listOf("calcTask", "approveTask"), conditions.map { it["from"] })
        assertTrue((conditions[0]["condition"] as String).contains("total > 100"))

        // the process carries no modelRefs (no extension refs / sequences)
        assertFalse(p.containsKey("modelRefs"))

        // cross-model references recorded on ctx
        val r = refs(ctx)
        assertTrue(r.containsAll(setOf(
            Triple("userTask-form", "form", "orderForm"),
            Triple("start-form", "form", "orderForm"),
            Triple("serviceTask-delegate", "bean", "notifierBean"),
            Triple("callActivity", "process", "fulfilmentProcess"),
            Triple("ruleTask-decision", "decision", "orderDecision"),
            Triple("receives-event", "event", "orderShipped"),
        )))

        // literal starter/candidate groups feed the group index
        assertTrue(ctx.groups.containsAll(setOf("sales", "backoffice")))
    }

    @Test
    fun parseCmmnPlanModel() {
        val ctx = Ctx()
        val cases = BackendModelParsers.parseCmmn(bytes("miniproject/cases/review.cmmn"), ctx, "cases/review.cmmn")

        assertEquals(listOf("reviewCase"), cases.map { it["key"] })
        val c = cases[0]
        assertEquals("Review Case", c["name"])
        assertEquals("auditors", c["candidateStarterGroups"])

        @Suppress("UNCHECKED_CAST")
        val planModel = c["planModel"] as Map<String, Any?>
        assertEquals("planModel", planModel["id"])
        assertEquals("casePlanModel", planModel["type"])

        val kids = listOfMaps(planModel["children"])
        assertEquals(listOf("reviewTask", "startOrder", "lookupTask"), kids.map { it["id"] })

        // human task leaf
        val reviewTask = kids[0]
        assertEquals("humanTask", reviewTask["type"])
        assertEquals("orderForm", reviewTask["formKey"])
        assertEquals("auditors", reviewTask["candidateGroups"])
        assertEquals(emptyMap<String, Any?>(), reviewTask["rules"])

        // process task leaf resolves its calledElement / processRef
        val startOrder = kids[1]
        assertEquals("processTask", startOrder["type"])
        assertEquals("orderProcess", startOrder["processRef"])

        // service-mapping task leaf resolves its service model + operation
        val lookupTask = kids[2]
        assertEquals("customerService", lookupTask["serviceModelKey"])
        assertEquals("findAll", lookupTask["operationKey"])

        // no sentries / milestones / event listeners in this case
        assertEquals(emptyList<Any?>(), c["sentries"])
        assertEquals(emptyList<Any?>(), c["milestones"])
        assertEquals(emptyList<Any?>(), c["eventListeners"])
        assertEquals(emptyList<Any?>(), c["modelRefs"])

        // cross-model references recorded on ctx
        val r = refs(ctx)
        assertTrue(r.containsAll(setOf(
            Triple("humanTask-form", "form", "orderForm"),
            Triple("processTask", "process", "orderProcess"),
            Triple("serviceMapping", "service", "customerService"),
        )))

        // the service mapping also records an operation-level usage (reviewCase -> customerService#findAll)
        assertTrue(ctx.opUse.any {
            it["consumer"] == "reviewCase" && it["targetKind"] == "service" &&
                it["targetKey"] == "customerService" && it["op"] == "findAll"
        })

        // starter/candidate groups feed the group index
        assertTrue(ctx.groups.contains("auditors"))
    }
}
