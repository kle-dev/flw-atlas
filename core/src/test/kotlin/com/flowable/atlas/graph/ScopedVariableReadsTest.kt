package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Reads the variable graph used to drop: a name behind a scope token (`${root.x}`, `{{parent.x}}`) and a
 * name past the head of a binding (`{{flw.formatDate(x, 'DD')}}`, `{{a && b}}`, `{{!x}}`). Each one left a
 * variable that something does read reported as written but never read — and the remedy that report
 * offers is to delete the write.
 */
class ScopedVariableReadsTest {

    private val d = "$"

    private fun extract(files: Map<String, String>): Map<String, Any?> {
        val dir = Files.createTempDirectory("atlas-scoped-reads").toFile()
        try {
            for ((path, text) in files) File(dir, path).apply { parentFile.mkdirs() }.writeText(text.trimIndent())
            return Atlas.extract(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.variable(name: String): Map<String, Any?>? =
        ((this["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>)
            .firstOrNull { it["id"] == "variable:$name" }?.get("data") as Map<String, Any?>?

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.reads(name: String): List<String> =
        ((variable(name)?.get("reads") as? List<Map<String, Any?>>).orEmpty()).map { "${it["via"]}@${it["model"]}" }.sorted()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.findings(check: String): List<String> =
        (this["findings"] as List<Map<String, Any?>>).filter { it["check"] == check }.map { it["node"].toString() }

    private fun process(id: String, body: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://example.com">
          <process id="$id" name="$id">$body</process>
        </definitions>"""

    private fun initVars(vararg names: String) =
        """<serviceTask id="init" name="Initialize" flowable:delegateExpression="${d}{initVariablesService}"><extensionElements>""" +
            names.joinToString("") { """<flowable:variableMapping name="$it" valueType="string" value="x"/>""" } +
            "</extensionElements></serviceTask>"

    @Test
    fun aRootReadInAConditionReadsTheVariable() {
        val r = extract(mapOf("p.bpmn20.xml" to process("DEMO-root", """
            <startEvent id="start"/><sequenceFlow id="f1" sourceRef="start" targetRef="setIt"/>
            <scriptTask id="setIt" name="Set it" scriptFormat="groovy"><script><![CDATA[execution.setVariable("approvedFlag", true)]]></script></scriptTask>
            <sequenceFlow id="f2" sourceRef="setIt" targetRef="gw"/><exclusiveGateway id="gw" default="fNo"/>
            <sequenceFlow id="fYes" sourceRef="gw" targetRef="end"><conditionExpression><![CDATA[${d}{root.approvedFlag}]]></conditionExpression></sequenceFlow>
            <sequenceFlow id="fNo" sourceRef="gw" targetRef="end"/><endEvent id="end"/>
            <userTask id="u" flowable:assignee="${d}{demoService.pick('k', parent.reviewer)}"/>
            <userTask id="v" flowable:assignee="${d}{self.ownAssignee}"/>""")))
        assertEquals(listOf("rootScope@process:DEMO-root"), r.reads("approvedFlag"))
        assertTrue("a scoped name as a call argument is a read too", r.reads("reviewer").contains("parentScope@process:DEMO-root"))
        assertEquals("`self.` is the instance's own scope", listOf("expression@process:DEMO-root"), r.reads("ownAssignee"))
        assertTrue(r.findings("unusedVars").isEmpty())
    }

    @Test
    fun aCalledProcessBranchingOnTheRootsVariableReadsIt() {
        val r = extract(mapOf(
            "parent.cmmn" to """<?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" xmlns:flowable="http://flowable.org/cmmn" targetNamespace="http://example.com">
                  <case id="DEMO-case" name="Demo case"><casePlanModel id="planModel" name="Plan">
                    <planItem id="pi1" definitionRef="init"/><planItem id="pi2" definitionRef="pt"/>
                    <task id="init" name="Initialize" flowable:type="java" flowable:delegateExpression="${d}{initVariablesService}">
                      <extensionElements><flowable:serviceTaskType><![CDATA[initVariables]]></flowable:serviceTaskType>
                        <flowable:variableMapping name="riskLevel" value="HIGH"/><flowable:variableMapping name="customerName" value="x"/></extensionElements>
                    </task>
                    <processTask id="pt" name="Review"><processRefExpression><![CDATA[DEMO-review]]></processRefExpression></processTask>
                  </casePlanModel></case>
                </definitions>""",
            "child.bpmn20.xml" to process("DEMO-review", """
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="gw"/><exclusiveGateway id="gw" default="f3"/>
                <sequenceFlow id="f2" sourceRef="gw" targetRef="ut"><conditionExpression><![CDATA[${d}{root.riskLevel == 'HIGH'}]]></conditionExpression></sequenceFlow>
                <sequenceFlow id="f3" sourceRef="gw" targetRef="e"/><userTask id="ut" name="Check" flowable:formKey="DEMO-review-form"/>
                <sequenceFlow id="f4" sourceRef="ut" targetRef="e"/><endEvent id="e"/>"""),
            "f.form" to """{"metadata":{"key":"DEMO-review-form","name":"Review","modelType":"form"},"rows":[{"cols":[
                {"id":"hdr","type":"text","label":"Customer","value":"{{root.customerName}}"}]}]}""",
        ))
        assertEquals(listOf("rootScope@process:DEMO-review"), r.reads("riskLevel"))
        assertEquals(listOf("rootScope@form:DEMO-review-form"), r.reads("customerName"))
        assertTrue(r.findings("unusedVars").isEmpty())
    }

    @Test
    fun anInstanceFieldBehindAScopeTokenIsNoVariable() {
        val r = extract(mapOf(
            "p.bpmn20.xml" to process("DEMO-fields", """
                <scriptTask id="t" scriptFormat="groovy"><script><![CDATA[execution.setVariable("name", "x")]]></script></scriptTask>
                <userTask id="u" flowable:formKey="DEMO-fields-form"
                  flowable:assignee="${d}{root.businessKey}${d}{root.getVariable('hidden')}${d}{parent.startTime}"/>"""),
            "f.form" to """{"metadata":{"key":"DEMO-fields-form","name":"F","modelType":"form"},"rows":[[
                {"id":"a","type":"text","label":"A","value":"{{root.endTime}} {{parent.name}}"}]]}""",
        ))
        for (n in listOf("businessKey", "getVariable", "hidden", "startTime", "endTime")) assertNull(n, r.variable(n))
        // …but a variable of that name exists here, so the read may be one of it — and a suspected read counts
        assertEquals(listOf("parentScope@form:DEMO-fields-form"), r.reads("name"))
        assertTrue(r.findings("unusedVars").isEmpty())
    }

    @Test
    fun everyNameOfABindingIsARead() {
        // The audit's shape: six init variables, each shown by a form only past the head of its binding.
        val r = extract(mapOf(
            "p.bpmn20.xml" to process("DEMO-b", """
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="init"/>
                ${initVars("ownerA", "ownerB", "ownerC", "ownerD", "ownerE", "ownerF", "ownerG")}
                <sequenceFlow id="f2" sourceRef="init" targetRef="u"/><userTask id="u" name="Look" flowable:formKey="DEMO-f"/>
                <sequenceFlow id="f3" sourceRef="u" targetRef="e"/><endEvent id="e"/>"""),
            "f.form" to """{"metadata":{"key":"DEMO-f","name":"F","modelType":"form"},"rows":[
                [{"id":"h1","type":"htmlComponent","value":"<p>{{flw.getUser(parent.ownerA).displayName}}</p>"}],
                [{"id":"h2","type":"htmlComponent","value":"<p>{{root.ownerB}}</p>"}],
                [{"id":"h3","type":"htmlComponent","value":"<p>{{flw.formatDate(ownerC, 'DD.MM.YYYY')}}</p>"}],
                [{"id":"h4","type":"text","label":"x","value":"{{other}}","visible":"{{ownerD && ownerE}}"}],
                [{"id":"h5","type":"text","label":"y","value":"{{other2}}","visible":"{{!ownerF}}"}],
                [{"id":"h6","type":"text","label":"z","value":"{{${d}payload.ownerG}}"}],
                [{"id":"h7","type":"text","label":"w","value":"{{${d}currentUser.id}} {{${d}temp.scratch}} {{rows.map(i => i.amount)}}"}]]}""",
        ))
        assertTrue(r.findings("unusedVars").toString(), r.findings("unusedVars").isEmpty())
        assertEquals(listOf("binding@form:DEMO-f"), r.reads("ownerC"))
        assertEquals(listOf("parentScope@form:DEMO-f"), r.reads("ownerA"))
        assertEquals(listOf("rootScope@form:DEMO-f"), r.reads("ownerB"))
        for (n in listOf("ownerE", "ownerF", "ownerG")) assertEquals(n, listOf("binding@form:DEMO-f"), r.reads(n))
        // a name past the head never makes a variable of its own, and the runtime's scratch never does
        for (n in listOf("currentUser", "scratch", "i", "amount", "displayName")) assertNull(n, r.variable(n))
    }

    /** A caller mapping `x` into a child process whose form shows it through [binding]. */
    private fun calleeReading(binding: String) = extract(mapOf(
        "caller.bpmn20.xml" to process("DEMO-caller", """
            <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="init"/>${initVars("handedOver")}
            <sequenceFlow id="f2" sourceRef="init" targetRef="call"/>
            <callActivity id="call" name="Call" calledElement="DEMO-callee"><extensionElements>
              <flowable:in source="handedOver" target="handedOver"/></extensionElements></callActivity>
            <sequenceFlow id="f3" sourceRef="call" targetRef="e"/><endEvent id="e"/>"""),
        "callee.bpmn20.xml" to process("DEMO-callee", """
            <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="u"/>
            <userTask id="u" name="Look" flowable:formKey="DEMO-callee-form"/><sequenceFlow id="f2" sourceRef="u" targetRef="e"/><endEvent id="e"/>"""),
        "f.form" to """{"metadata":{"key":"DEMO-callee-form","name":"F","modelType":"form"},"rows":[[
            {"id":"h","type":"htmlComponent","value":"<p>$binding</p>"}]]}""",
    ))

    @Test
    fun aCalleeReadingTheParentsCopyStillIgnoresTheMapping() {
        // `{{parent.handedOver}}` in the callee's form is the caller's variable: the mapping into the callee
        // is still unread, and that finding is true.
        assertEquals(listOf("variable:handedOver"), calleeReading("{{flw.getUser(parent.handedOver)}}").findings("unreadInputs"))
        // the callee's own copy, read past the head of a binding, is a read of the mapping
        assertTrue(calleeReading("{{flw.formatDate(handedOver, 'DD')}}").findings("unreadInputs").isEmpty())
    }

    @Test
    fun aDecisionInputThroughTheRootReadsTheVariable() {
        val r = extract(mapOf(
            "t.dmn" to """<?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="http://www.flowable.org/dmn">
                  <decision id="DEMO-table" name="Table"><decisionTable id="dt" hitPolicy="FIRST">
                    <input id="i1" label="Amount"><inputExpression id="ie1" typeRef="number"><text>root.amount</text></inputExpression></input>
                    <output id="o1" label="Risk" name="risk" typeRef="string"/>
                  </decisionTable></decision>
                </definitions>""",
        ))
        assertEquals(listOf("dmnInput@decision:DEMO-table"), r.reads("amount"))
    }
}
