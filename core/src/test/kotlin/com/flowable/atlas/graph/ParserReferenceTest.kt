package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A model setting is a reference only where the engine or the platform reads it as one. */
class ParserReferenceTest {

    private fun extract(files: Map<String, String>): Map<String, Any?> {
        val dir = Files.createTempDirectory("atlas-parser-refs").toFile()
        try {
            for ((path, text) in files) File(dir, path).apply { parentFile.mkdirs() }.writeText(text)
            return Atlas.extract(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.edges() = ((this["graph"] as Map<String, Any?>)["edges"] as List<Map<String, Any?>>)
        .map { Triple(it["s"], it["rel"], it["t"]) }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.missing() = (this["findings"] as List<Map<String, Any?>>)
        .filter { it["check"] == "missingRefs" }.map { it["node"] }

    @Test
    fun anActionsSignalNameIsWhatItsBotReadsItAs() {
        val r = extract(mapOf(
            "actions/set.action" to """{"key":"DEMO-A1","name":"Set","botKey":"demo-set-variable-bot","signalName":"approvedFlag"}""",
            "actions/sig.action" to """{"key":"DEMO-A2","name":"Signal","botKey":"platform-signal-process-bot","signalName":"DEMO-cancel"}""",
            "actions/inj.action" to """{"key":"DEMO-A3","name":"Inject","botKey":"bpmn-inject-dynamic-subprocess-bot","signalName":"DEMO-sub"}""",
        ))
        val e = r.edges()
        assertFalse("a custom bot's signalName is its own business: $e", e.any { it.first == "action:DEMO-A1" && it.second != "bot" })
        assertTrue(e.contains(Triple("action:DEMO-A2", "triggers-signal", "signal:DEMO-cancel")))
        assertTrue(e.any { it.first == "action:DEMO-A3" && it.second == "starts-process" })
    }

    @Test
    fun aJuelHashExpressionIsDynamic() {
        val r = extract(mapOf("processes/p.bpmn" to """<definitions xmlns:flowable="http://flowable.org/bpmn">
              <process id="DEMO-P"><callActivity id="c" calledElement="#{subProcessKey}"/>
              <userTask id="t" flowable:formKey="#{taskFormKey}"/></process></definitions>"""))
        assertEquals(emptyList<Any?>(), r.missing())
    }

    @Test
    fun aMappedFormKeyVariableIsNoFormKey() {
        val r = extract(mapOf("processes/p.bpmn" to """<definitions xmlns:flowable="http://flowable.org/bpmn">
              <process id="DEMO-P"><callActivity id="c" calledElement="DEMO-child"><extensionElements>
                <flowable:in source="chosenFormKey" target="formKey"/>
                <flowable:in sourceExpression="DEMO-F9" target="formKey"/>
              </extensionElements></callActivity></process></definitions>"""))
        val targets = r.edges().filter { it.second == "task-form-mapping" }.map { it.third }
        assertEquals(listOf("external:DEMO-F9"), targets)
    }

    @Test
    fun anExternalAgentPropertyIsAnEventOnlyWhereItNamesOne() {
        val r = extract(mapOf(
            "events/ev.event" to """{"key":"DEMO-EV1","name":"Inbound"}""",
            "agents/ag.agent" to """{"key":"DEMO-AG1","name":"External","type":"externalAgent",
                "externalAgentSettings":{"type":"demoVendor","properties":{
                  "promptTemplate":{"id":"TPL-1","key":"DEMO-TPL1"},"inboundEvent":{"key":"DEMO-EV1"}}}}""",
        ))
        assertTrue(r.edges().contains(Triple("agent:DEMO-AG1", "agent-event", "event:DEMO-EV1")))
        assertEquals(emptyList<Any?>(), r.missing())
    }
}
