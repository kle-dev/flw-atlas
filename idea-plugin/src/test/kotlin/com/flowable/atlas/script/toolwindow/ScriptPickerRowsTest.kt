package com.flowable.atlas.script.toolwindow

import com.flowable.atlas.graph.Ctx
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.parsing.BackendModelParsers
import com.flowable.atlas.parsing.ModelParsers
import com.flowable.atlas.script.ScriptContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The picker's flattening finds every script the :core parsers surface — and only those. */
class ScriptPickerRowsTest : BasePlatformTestCase() {

    private val bpmn = """<?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                     xmlns:flowable="http://flowable.org/bpmn">
          <process id="orderProcess" name="Order">
            <scriptTask id="stamp" name="Stamp" scriptFormat="groovy">
              <script>execution.setVariable('x', 1)</script>
            </scriptTask>
            <scriptTask id="empty" name="Empty" scriptFormat="groovy"><script></script></scriptTask>
            <serviceTask id="notify" name="Notify" flowable:delegateExpression="${'$'}{bean}">
              <extensionElements>
                <flowable:executionListener event="end">
                  <flowable:script scriptFormat="juel">${'$'}{done}</flowable:script>
                </flowable:executionListener>
              </extensionElements>
            </serviceTask>
          </process>
        </definitions>"""

    private val cmmn = """<?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
                     xmlns:flowable="http://flowable.org/cmmn">
          <case id="reviewCase" name="Review">
            <casePlanModel id="plan">
              <planItem id="pi1" definitionRef="scr"/>
              <task id="scr" name="Case script" flowable:type="script" flowable:scriptFormat="javascript">
                <extensionElements>
                  <flowable:field name="script"><flowable:string>var a = 1;</flowable:string></flowable:field>
                </extensionElements>
              </task>
            </casePlanModel>
          </case>
        </definitions>"""

    private val action = """{"key":"notify-bot","name":"Notify bot","botKey":"script-bot",
        "config":{"scriptInfo":{"language":"javascript","script":"const a = 1;"}}}"""

    fun testBpmnRows() {
        val procs = BackendModelParsers.parseBpmn(bpmn.toByteArray(), Ctx(), "order.bpmn")
        val rows = procs.flatMap { ScriptPicker.rowsFromModel(it, it["key"], "order.bpmn", ModelType.PROCESS) }
            .distinctBy { Triple(it.elementId, it.kind, it.body) }
        val stamp = rows.single { it.kind == "script task" }
        assertEquals("stamp", stamp.elementId)
        assertEquals("Stamp", stamp.elementName)
        assertEquals("groovy", stamp.format)
        assertEquals("execution.setVariable('x', 1)", stamp.body)
        assertEquals("orderProcess", stamp.modelKey)
        assertEquals(ScriptContext.BPMN_SCRIPT_TASK, stamp.context)
        // the listener inherits the element it hangs off; the empty script task yields no row
        val listener = rows.single { it.kind == "listener" }
        assertEquals("notify", listener.elementId)
        assertEquals("juel", listener.format)
        assertEquals(ScriptContext.BPMN_EXECUTION_LISTENER, listener.context)
        assertEquals(2, rows.size)
    }

    fun testCmmnRows() {
        val cases = BackendModelParsers.parseCmmn(cmmn.toByteArray(), Ctx(), "review.cmmn")
        val rows = cases.flatMap { ScriptPicker.rowsFromModel(it, it["key"], "review.cmmn", ModelType.CASE) }
        val row = rows.single()
        assertEquals("scr", row.elementId)
        assertEquals("javascript", row.format)
        assertEquals("var a = 1;", row.body)
        assertEquals("reviewCase", row.modelKey)
        assertEquals(ScriptContext.CMMN_SCRIPT_TASK, row.context)
    }

    fun testActionRows() {
        val rec = ModelParsers.parseAction(action.toByteArray(), Ctx(), "notify.action")
        val row = ScriptPicker.rowsFromModel(rec, null, "notify.action", ModelType.ACTION).single()
        assertEquals("action bot", row.kind)
        assertEquals("javascript", row.format)
        assertEquals("const a = 1;", row.body)
        assertEquals("notify-bot", row.modelKey)
        assertEquals(ScriptContext.ACTION_BOT, row.context)
    }

    fun testCollectRowsThroughTheIndex() {
        myFixture.addFileToProject("models/order.bpmn", bpmn)
        myFixture.addFileToProject("models/notify.action", action)
        project.getService(FlowableModelIndexService::class.java).refresh()
        val rows = ScriptPicker.collectRows(project) {}
        assertTrue(rows.any { it.kind == "script task" && it.elementId == "stamp" })
        assertTrue(rows.any { it.kind == "action bot" && it.modelKey == "notify-bot" })
    }
}
