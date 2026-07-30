package com.flowable.atlas.parsing

import com.flowable.atlas.graph.Ctx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parsers attach [com.flowable.atlas.script.ScriptValidator] findings to the script records. */
class ScriptProblemWiringTest {

    @Suppress("UNCHECKED_CAST")
    private fun problemsOf(rec: Map<String, Any?>?): List<Map<String, Any?>>? =
        rec?.get("problems") as? List<Map<String, Any?>>

    @Test
    fun bpmnScriptTaskCarriesItsProblems() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn">
              <process id="p">
                <scriptTask id="bad" name="Broken" scriptFormat="groovy">
                  <script>execution.setVariable('x', (1 + 2</script>
                </scriptTask>
                <scriptTask id="good" name="Fine" scriptFormat="groovy">
                  <script>execution.setVariable('x', 1)</script>
                </scriptTask>
              </process>
            </definitions>"""
        val procs = BackendModelParsers.parseBpmn(xml.toByteArray(), Ctx(), "p.bpmn")
        val tasks = (procs.single()["scriptTasks"] as List<*>).map { it as Map<String, Any?> }
        val bad = problemsOf(tasks.first { it["id"] == "bad" })!!
        assertEquals(1, bad.size)
        assertEquals("error", bad[0]["severity"])
        assertEquals("unclosed-opener", bad[0]["kind"])
        assertEquals(1, bad[0]["line"])
        // a clean script gets no problems key at all — no [] noise in the payload
        assertNull(problemsOf(tasks.first { it["id"] == "good" }))
    }

    @Test
    fun bpmnScriptTaskWithoutFormatGetsAWarning() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
              <process id="p">
                <scriptTask id="nofmt" name="No format">
                  <script>execution.setVariable('x', 1)</script>
                </scriptTask>
              </process>
            </definitions>"""
        val procs = BackendModelParsers.parseBpmn(xml.toByteArray(), Ctx(), "p.bpmn")
        val task = (procs.single()["scriptTasks"] as List<*>).map { it as Map<String, Any?> }.single()
        val problems = problemsOf(task)!!
        assertEquals(listOf("missing-format"), problems.map { it["kind"] })
        assertEquals("warning", problems[0]["severity"])
    }

    @Test
    fun cmmnScriptTaskCarriesItsProblems() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
                         xmlns:flowable="http://flowable.org/cmmn">
              <case id="c" name="Case">
                <casePlanModel id="plan">
                  <planItem id="pi1" definitionRef="scr"/>
                  <task id="scr" name="Broken stamp" flowable:type="script" flowable:scriptFormat="groovy">
                    <extensionElements>
                      <flowable:field name="script">
                        <flowable:string>def s = 'abc
foo()</flowable:string>
                      </flowable:field>
                    </extensionElements>
                  </task>
                </casePlanModel>
              </case>
            </definitions>"""
        val cases = BackendModelParsers.parseCmmn(xml.toByteArray(), Ctx(), "c.cmmn")
        val node = findById(cases.single()["planModel"], "scr")
        val problems = problemsOf(node)!!
        assertEquals(listOf("unterminated-string"), problems.map { it["kind"] })
    }

    @Test
    fun listenerScriptCarriesFormatAndProblems() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn">
              <process id="p">
                <serviceTask id="notify" name="Notify" flowable:delegateExpression="${'$'}{notifierBean}">
                  <extensionElements>
                    <flowable:executionListener event="end">
                      <flowable:script scriptFormat="groovy">execution.setVariable('done', [1, 2)</flowable:script>
                    </flowable:executionListener>
                  </extensionElements>
                </serviceTask>
              </process>
            </definitions>"""
        val procs = BackendModelParsers.parseBpmn(xml.toByteArray(), Ctx(), "p.bpmn")
        val task = (procs.single()["serviceTasks"] as List<*>).map { it as Map<String, Any?> }.single()
        val listener = (task["listeners"] as List<*>).map { it as Map<String, Any?> }.single()
        assertEquals("groovy", listener["scriptFormat"])
        val problems = problemsOf(listener)!!
        assertEquals(listOf("mismatched-closer"), problems.map { it["kind"] })
    }

    @Test
    fun taskListenerScriptIsValidatedAgainstTaskBindings() {
        // `execution` does not exist in a BPMN task-listener script — the engine binds `task`
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn">
              <process id="p">
                <userTask id="approve" name="Approve">
                  <extensionElements>
                    <flowable:taskListener event="complete">
                      <flowable:script scriptFormat="groovy">execution.setVariable('done', true)</flowable:script>
                    </flowable:taskListener>
                  </extensionElements>
                </userTask>
              </process>
            </definitions>"""
        val procs = BackendModelParsers.parseBpmn(xml.toByteArray(), Ctx(), "p.bpmn")
        val task = (procs.single()["userTasks"] as List<*>).map { it as Map<String, Any?> }.single()
        val listener = (task["listeners"] as List<*>).map { it as Map<String, Any?> }.single()
        val problems = problemsOf(listener)!!
        assertEquals(listOf("wrong-context-root"), problems.map { it["kind"] })
        assertTrue((problems[0]["message"] as String).contains("task"))
    }

    @Test
    fun cmmnLifecycleListenerWithScriptIsFlaggedAsUnsupported() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
                         xmlns:flowable="http://flowable.org/cmmn">
              <case id="c" name="Case">
                <casePlanModel id="plan">
                  <planItem id="pi1" definitionRef="ht"/>
                  <humanTask id="ht" name="Review">
                    <extensionElements>
                      <flowable:planItemLifecycleListener sourceState="available" targetState="active">
                        <flowable:script scriptFormat="groovy">planItemInstance.setVariable('x', 1)</flowable:script>
                      </flowable:planItemLifecycleListener>
                    </extensionElements>
                  </humanTask>
                </casePlanModel>
              </case>
            </definitions>"""
        val cases = BackendModelParsers.parseCmmn(xml.toByteArray(), Ctx(), "c.cmmn")
        val node = findById(cases.single()["planModel"], "ht")!!
        val listener = (node["listeners"] as List<*>).map { it as Map<String, Any?> }.single()
        val problems = problemsOf(listener)!!
        assertEquals(listOf("unsupported-script-listener"), problems.map { it["kind"] })
        assertEquals("warning", problems[0]["severity"])
    }

    @Test
    fun actionBotScriptCarriesScriptProblems() {
        val json = """
            {"key": "notify-bot", "name": "Notify", "botKey": "script-bot",
             "config": {"scriptInfo": {"language": "javascript", "script": "const s = `a\n"}}}
        """.trimIndent()
        val rec = ModelParsers.parseAction(json.toByteArray(), Ctx(), "notify.action")
        @Suppress("UNCHECKED_CAST")
        val problems = rec["scriptProblems"] as List<Map<String, Any?>>
        assertEquals(listOf("unterminated-string"), problems.map { it["kind"] })
        assertTrue((problems[0]["message"] as String).contains("template literal"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun findById(node: Any?, id: String): Map<String, Any?>? {
        val m = node as? Map<String, Any?> ?: return null
        if (m["id"] == id) return m
        for (c in (m["children"] as? List<*> ?: emptyList<Any?>())) {
            findById(c, id)?.let { return it }
        }
        return null
    }
}
