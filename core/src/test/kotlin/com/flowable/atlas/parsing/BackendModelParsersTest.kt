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
        // the security policy the plan model names is the case's own
        assertEquals(listOf(mapOf("rel" to "security-policy-model", "key" to "orderPolicy")), c["modelRefs"])

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

    /**
     * The event contract tables join an event's publishers and consumers element by element — which task
     * sends it, over which channel — and a signal's throw and catch by the name the shared node is keyed
     * by, not by each model's own definition id.
     */
    @Test
    fun anElementSaysWhichEventAndChannelItUses() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn">
              <signal id="sigDone" name="customerOnboarded"/>
              <process id="p">
                <startEvent id="start"><extensionElements><flowable:eventType>customerCreated</flowable:eventType></extensionElements></startEvent>
                <serviceTask id="announce" flowable:type="send-event">
                  <extensionElements>
                    <flowable:eventType>customerCreated</flowable:eventType>
                    <flowable:channelKey>customerEventsOut</flowable:channelKey>
                  </extensionElements>
                </serviceTask>
                <serviceTask id="legacy" flowable:type="send-event">
                  <extensionElements><flowable:field name="eventType"><flowable:string>orderShipped</flowable:string></flowable:field></extensionElements>
                </serviceTask>
                <intermediateThrowEvent id="tell"><signalEventDefinition signalRef="sigDone"/></intermediateThrowEvent>
              </process>
            </definitions>"""
        val p = BackendModelParsers.parseBpmn(xml.toByteArray(), Ctx(), "p.bpmn")[0]
        val events = listOfMaps(p["events"]).associateBy { it["id"] }
        assertEquals("customerCreated", events["start"]!!["eventType"])
        assertEquals("receive", events["start"]!!["eventRole"])
        assertEquals("the name, not the definition id", "customerOnboarded", events["tell"]!!["ref"])
        val tasks = listOfMaps(p["serviceTasks"]).associateBy { it["id"] }
        assertEquals("send", tasks["announce"]!!["eventRole"])
        assertEquals("customerEventsOut", tasks["announce"]!!["channelKey"])
        assertEquals("a send-event task configured by field", "orderShipped", tasks["legacy"]!!["eventType"])
    }

    /** The engine spells it `adHocSubProcess`, and tags compare case-sensitively: under the misspelling
     *  `adhocSubProcess` the element was not listed and its tasks had no `parent`. */
    @Test
    fun anAdHocSubProcessIsListedAndScopesItsTasks() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <process id="p">
                <adHocSubProcess id="adhoc1" name="Pick any">
                  <userTask id="review"/>
                </adHocSubProcess>
              </process>
            </definitions>"""
        val p = BackendModelParsers.parseBpmn(xml.toByteArray(), Ctx(), "p.bpmn")[0]
        val sp = listOfMaps(p["subProcesses"]).single()
        assertEquals("adhoc1", sp["id"])
        assertEquals("adHocSubProcess", sp["type"])
        assertEquals("adhoc1", listOfMaps(p["userTasks"]).single()["parent"])
    }

    /**
     * A hand-written CMMN Java task names its implementation on the `<task>` itself, with no
     * `<extensionElements>` at all — the engine's TaskXmlConverter reads it there. Returning early on the
     * missing element dropped the bean reference, and `flowable:class` was never a reference at all.
     */
    @Test
    fun aCmmnJavaTaskWithoutExtensionElementsKeepsItsImplementation() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" xmlns:flowable="http://flowable.org/cmmn">
              <case id="c">
                <extensionElements>
                  <flowable:caseLifecycleListener sourceState="active" targetState="completed" delegateExpression="${'$'}{caseEndListener}"/>
                </extensionElements>
                <casePlanModel id="plan">
                  <planItem id="pi1" definitionRef="byClass"/>
                  <planItem id="pi2" definitionRef="byBean"/>
                  <planItem id="pi3" definitionRef="human"/>
                  <task id="byClass" flowable:type="java" flowable:class="com.example.DemoDelegate"/>
                  <task id="byBean" flowable:type="java" flowable:delegateExpression="${'$'}{demoBean}"/>
                  <humanTask id="human"/>
                </casePlanModel>
              </case>
            </definitions>"""
        val ctx = Ctx()
        val case = BackendModelParsers.parseCmmn(xml.toByteArray(), ctx, "c.cmmn")[0]
        assertTrue(refs(ctx).containsAll(setOf(
            Triple("task-class", "class", "com.example.DemoDelegate"),
            Triple("task-delegate", "bean", "demoBean"),
            Triple("caseLifecycleListener:completed", "bean", "caseEndListener"),
        )))
        @Suppress("UNCHECKED_CAST")
        val items = listOfMaps((case["planModel"] as Map<String, Any?>)["children"]).associateBy { it["id"] }
        assertEquals("com.example.DemoDelegate", items["byClass"]!!["class"])
        assertEquals("\${demoBean}", items["byBean"]!!["delegateExpression"])
        assertFalse("a task that names no implementation keeps its shape", items["human"]!!.containsKey("class"))
        val listener = listOfMaps(case["listeners"]).single()
        assertEquals("caseLifecycleListener", listener["kind"])
        assertEquals("completed", listener["event"])
    }

    /** A case lifecycle listener with a script is a silent no-op: CmmnListenerFactory has no script branch
     *  for it, as for a plan item's — so it carries the same problem. */
    @Test
    fun aScriptOnACaseLifecycleListenerIsFlagged() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" xmlns:flowable="http://flowable.org/cmmn">
              <case id="c">
                <extensionElements>
                  <flowable:caseLifecycleListener targetState="completed"><flowable:script scriptFormat="groovy">x = 1</flowable:script></flowable:caseLifecycleListener>
                </extensionElements>
                <casePlanModel id="plan"/>
              </case>
            </definitions>"""
        val case = BackendModelParsers.parseCmmn(xml.toByteArray(), Ctx(), "c.cmmn")[0]
        val problems = listOfMaps(listOfMaps(case["listeners"]).single()["problems"])
        assertEquals("unsupported-script-listener", problems.single()["kind"])
    }

    /** A process's documentation is its own `<documentation>` child — never the first one below it, which
     *  on the public demo turned a user task's TODO into the process description. */
    @Test
    fun aProcessIsDescribedOnlyByItsOwnDocumentation() {
        fun process(own: String) = BackendModelParsers.parseBpmn("""<?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <process id="p">$own
                <userTask id="t"><documentation>DEMO task note</documentation></userTask>
              </process>
            </definitions>""".toByteArray(), Ctx(), "p.bpmn")[0]
        val bare = process("")
        assertEquals(null, bare["documentation"])
        assertEquals("DEMO task note", listOfMaps(bare["userTasks"]).single()["documentation"])
        assertEquals("DEMO process", process("<documentation>DEMO process</documentation>")["documentation"])
    }

    /** A case's documentation is its own, or its plan model's (where Design keeps the plan model shape's
     *  prose) — never a sentry's or a task's. */
    @Test
    fun aCaseIsDescribedByItsOwnOrItsPlanModelsDocumentation() {
        fun case(own: String, plan: String) = BackendModelParsers.parseCmmn("""<?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL">
              <case id="c">$own
                <casePlanModel id="plan">$plan
                  <sentry id="s"><documentation>DEMO sentry note</documentation></sentry>
                </casePlanModel>
              </case>
            </definitions>""".toByteArray(), Ctx(), "c.cmmn")[0]
        assertEquals(null, case("", "")["documentation"])
        assertEquals("DEMO plan", case("", "<documentation>DEMO plan</documentation>")["documentation"])
        assertEquals("DEMO case",
            case("<documentation>DEMO case</documentation>", "<documentation>DEMO plan</documentation>")["documentation"])
    }

    @Test
    fun aCaseWithoutListenersHasNoListenersKey() {
        val case = BackendModelParsers.parseCmmn(bytes("miniproject/cases/review.cmmn"), Ctx(), "cases/review.cmmn")[0]
        assertFalse(case.containsKey("listeners"))
    }
}
