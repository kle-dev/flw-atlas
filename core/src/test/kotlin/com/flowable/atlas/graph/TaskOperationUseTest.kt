package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * An operation a task calls is used — whichever way the task names it. A service-registry task
 * configured by field injection (`serviceKey` / `operationKey`) was invisible: its operation was reported
 * unused and nothing linked the process to the service.
 */
class TaskOperationUseTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-task-op-use-test").toFile()
            File(dir, "order.service").writeText(
                """{"key":"orderService","name":"Order Service","type":"rest","operations":[
                    {"key":"findByNumber","name":"Find by number","config":{"method":"GET","url":"/api/orders/{n}"},
                     "inputParameters":[{"name":"orderNumber","type":"string"}]},
                    {"key":"archive","name":"Archive","config":{"method":"POST","url":"/api/orders/archive"}}
                  ]}""")
            File(dir, "p.bpmn").writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
                  |<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn">
                  |  <process id="DEMO-p" name="P">
                  |    <startEvent id="s"/>
                  |    <serviceTask id="lookupOrder" name="Look up order" flowable:type="service-registry">
                  |      <extensionElements>
                  |        <flowable:field name="serviceKey"><flowable:string>orderService</flowable:string></flowable:field>
                  |        <flowable:field name="operationKey"><flowable:string>findByNumber</flowable:string></flowable:field>
                  |        <flowable:in source="orderNumber" target="orderNumber"/>
                  |      </extensionElements>
                  |    </serviceTask>
                  |    <scriptTask id="seed" scriptFormat="groovy"><script>execution.setVariable('orderNumber', '1')</script></scriptTask>
                  |    <sequenceFlow id="f1" sourceRef="s" targetRef="seed"/>
                  |    <sequenceFlow id="f2" sourceRef="seed" targetRef="lookupOrder"/>
                  |  </process>
                  |</definitions>
                  |""".trimMargin())
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun node(id: String) = ((result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>).single { it["id"] == id }

    @Suppress("UNCHECKED_CAST")
    private fun findings() = result["findings"] as List<Map<String, Any?>>

    @Test
    fun aFieldInjectedTaskUsesItsOperation() {
        @Suppress("UNCHECKED_CAST")
        val usedBy = (node("serviceOperation:orderService#findByNumber")["data"] as Map<String, Any?>)["usedBy"] as List<*>
        assertTrue(usedBy.toString(), "process:DEMO-p" in usedBy)
        assertFalse("findByNumber is not reported unused", findings().any {
            it["check"] == "unusedOps" && it["node"] == "serviceOperation:orderService#findByNumber" })
        assertTrue("archive still is", findings().any { it["check"] == "unusedOps" && it["node"] == "serviceOperation:orderService#archive" })
    }

    @Test
    fun theProcessIsLinkedToTheService() {
        @Suppress("UNCHECKED_CAST")
        val edges = (result["graph"] as Map<String, Any?>)["edges"] as List<Map<String, Any?>>
        assertTrue(edges.any { it["s"] == "process:DEMO-p" && it["t"] == "service:orderService" && it["rel"] == "serviceMapping" })
    }

    @Test
    fun theParameterItHandsOverIsNoUnreadVariable() {
        assertFalse(findings().any { it["check"] == "unreadInputs" || (it["check"] == "unusedVars" && "orderNumber" in (it["subject"]?.toString() ?: "")) })
    }
}
