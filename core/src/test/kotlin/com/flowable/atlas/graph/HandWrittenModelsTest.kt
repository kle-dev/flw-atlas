package com.flowable.atlas.graph

import com.flowable.atlas.render.OverviewRenderer
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Models written by hand against the engine's documented XML, the shape an open-source engine project
 * has, rather than exported by Design. Design always writes the extension elements and the long attribute
 * spellings, which is why every gap below went unnoticed on Design projects:
 *  - a CMMN Java task names its class or bean on the `<task>` itself, with no `<extensionElements>`;
 *  - a case's own `caseLifecycleListener` hangs off the `<case>`;
 *  - a service task's result goes to `flowable:resultVariable`, the spelling the BPMN user guide shows,
 *    and a CMMN Java task's to `flowable:resultVariableName`;
 *  - an ad-hoc sub-process is `<adHocSubProcess>`.
 */
class HandWrittenModelsTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-hand-written-test").toFile()
            fun put(rel: String, text: String) = File(dir, rel).apply { parentFile.mkdirs(); writeText(text.trimIndent()) }
            put("src/main/java/com/example/DemoDelegate.java", """
                package com.example;
                public class DemoDelegate implements PlanItemJavaDelegate {
                    public void execute(DelegatePlanItemInstance planItemInstance) {}
                }
            """)
            put("src/main/java/com/example/DemoBean.java", """
                package com.example;
                @Component("demoBean")
                public class DemoBean implements PlanItemJavaDelegate {
                    public void execute(DelegatePlanItemInstance planItemInstance) {}
                }
            """)
            put("src/main/java/com/example/CaseEndListener.java", """
                package com.example;
                @Component("caseEndListener")
                public class CaseEndListener implements CaseInstanceLifecycleListener {}
            """)
            put("src/main/java/com/example/DemoCalc.java", """
                package com.example;
                @Component("demoCalc")
                public class DemoCalc { public int total(Object scope) { return 1; } }
            """)
            put("src/main/java/com/example/Notifier.java", """
                package com.example;
                @Component("notifier")
                public class Notifier implements JavaDelegate { public void execute(DelegateExecution e) {} }
            """)
            put("cases/DEMO-case.cmmn", """
                <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" xmlns:flowable="http://flowable.org/cmmn">
                  <case id="DEMO-case" name="DEMO case">
                    <extensionElements>
                      <flowable:caseLifecycleListener sourceState="active" targetState="completed" delegateExpression="${'$'}{caseEndListener}"/>
                    </extensionElements>
                    <casePlanModel id="plan">
                      <planItem id="pi1" definitionRef="byClass"/>
                      <planItem id="pi2" definitionRef="byBean"/>
                      <planItem id="pi3" definitionRef="calcCase"/>
                      <task id="byClass" name="By class" flowable:type="java" flowable:class="com.example.DemoDelegate"/>
                      <task id="byBean" name="By bean" flowable:type="java" flowable:delegateExpression="${'$'}{demoBean}"/>
                      <task id="calcCase" name="Calculate" flowable:type="java"
                            flowable:expression="${'$'}{demoCalc.total(planItemInstance)}" flowable:resultVariableName="caseTotal"/>
                    </casePlanModel>
                  </case>
                </definitions>
            """)
            put("processes/DEMO-p.bpmn20.xml", """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn">
                  <process id="DEMO-p" name="DEMO process">
                    <startEvent id="start"/>
                    <serviceTask id="calc" name="Calculate" flowable:expression="${'$'}{demoCalc.total(execution)}" flowable:resultVariable="orderTotal"/>
                    <adHocSubProcess id="adhoc1" name="Pick any">
                      <userTask id="review"/>
                      <serviceTask id="notify" name="Notify" flowable:delegateExpression="${'$'}{notifier}"/>
                    </adHocSubProcess>
                    <boundaryEvent id="err" attachedToRef="adhoc1"><errorEventDefinition/></boundaryEvent>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="calc"/>
                    <sequenceFlow id="f2" sourceRef="calc" targetRef="adhoc1"/>
                    <sequenceFlow id="f3" sourceRef="adhoc1" targetRef="end"/>
                  </process>
                </definitions>
            """)
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolved(rel: String): Map<String, Any?>? =
        (result["resolvedRefs"] as List<Map<String, Any?>>).firstOrNull { it["rel"] == rel }

    @Suppress("UNCHECKED_CAST")
    private fun node(id: String): Map<String, Any?>? =
        ((result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>).firstOrNull { it["id"] == id }

    @Suppress("UNCHECKED_CAST")
    private fun findings(check: String): List<Map<String, Any?>> =
        (result["findings"] as List<Map<String, Any?>>).filter { it["check"] == check }

    @Test
    fun aCmmnJavaTaskLinksItsClassAndItsBeanWithoutExtensionElements() {
        assertEquals("com.example.DemoDelegate", resolved("task-class")?.get("targetFqn"))
        assertEquals("com.example.DemoBean", resolved("task-delegate")?.get("targetFqn"))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aCaseLifecycleListenerIsReadAndItsClassIsAListener() {
        val r = resolved("caseLifecycleListener:completed")
        assertNotNull("the case's listener bean was not resolved", r)
        assertEquals("bean", r!!["kind"])
        val roles = (node("java:com.example.CaseEndListener")!!["data"] as Map<String, Any?>)["roles"] as List<*>
        assertTrue("roles: $roles", "listener" in roles)
        val case = (result["cases"] as List<Map<String, Any?>>).single()
        assertEquals(1, (case["listeners"] as List<*>).size)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun eitherResultVariableSpellingIsTheVariablesWriter() {
        for (v in listOf("orderTotal", "caseTotal")) {
            val data = node("variable:$v")?.get("data") as? Map<String, Any?>
            assertNotNull("no variable node for $v", data)
            assertEquals(v, 1, data!!["writeCount"])
            assertEquals(v, listOf("resultVariable"), (data["writes"] as List<Map<String, Any?>>).map { it["via"] })
        }
    }

    @Test
    fun theOverviewNamesWhatEachTaskRunsAndWhereItsResultGoes() {
        val md = OverviewRenderer.render(result, dir)
        assertTrue(md, md.contains("→ \${demoCalc.total(execution)} → var `orderTotal`"))
        assertTrue(md, md.contains("class=`com.example.DemoDelegate`"))
        assertTrue(md, md.contains("delegateExpression=`\${demoBean}`"))
        assertTrue(md, md.contains("resultVariable=`caseTotal`"))
        assertTrue(md, md.contains("Case lifecycle listener [completed] → \${caseEndListener}"))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun anAdHocSubProcessScopesItsTasksAndItsErrorBoundaryGuardsThem() {
        val p = (result["processes"] as List<Map<String, Any?>>).single()
        assertEquals(listOf("adhoc1"), (p["subProcesses"] as List<Map<String, Any?>>).map { it["id"] })
        assertEquals("adhoc1", (p["userTasks"] as List<Map<String, Any?>>).single()["parent"])
        assertTrue("the error boundary on the ad-hoc sub-process guards the task inside it",
            findings("unguardedTasks").none { it["element"] == "notify" })
    }
}
