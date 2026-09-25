package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A record about a model lands on that model, never on another type that shares its key. Each case here
 * credited a same-key neighbour before: a first-wins key-only lookup stood behind all of them.
 */
class TypedAttributionTest {

    private val process = """<?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
          <process id="DEMO-X" name="Demo process"><startEvent id="s"/><endEvent id="e"/><sequenceFlow id="f" sourceRef="s" targetRef="e"/></process>
        </definitions>"""

    private fun caseXml(body: String = """<humanTask id="h" name="h"/><planItem id="pi" definitionRef="h"/>""") =
        """<?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" xmlns:flowable="http://flowable.org/cmmn" targetNamespace="t">
          <case id="DEMO-X" name="Demo case"><casePlanModel id="pm" name="p">$body</casePlanModel></case>
        </definitions>"""

    private fun extract(files: Map<String, String>): Map<String, Any?> {
        val dir = Files.createTempDirectory("atlas-typed-attribution").toFile()
        try {
            for ((path, text) in files) File(dir, path).apply { parentFile.mkdirs() }.writeText(text)
            return Atlas.extract(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.graph(part: String) = (this["graph"] as Map<String, Any?>)[part] as List<Map<String, Any?>>

    private fun Map<String, Any?>.edges(rel: String) = graph("edges").filter { it["rel"] == rel }.map { it["s"] to it["t"] }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.node(id: String) = graph("nodes").single { it["id"] == id }["data"] as Map<String, Any?>

    @Test
    fun aKeyPassedToATypedApiNamesAModelOfThatType() {
        val r = extract(mapOf(
            "processes/x.bpmn" to process,
            "cases/x.cmmn" to caseXml(),
            "src/main/java/com/example/Starter.java" to """
                package com.example;
                public class Starter {
                    void start(Object cmmnRuntimeService, Object runtimeService) {
                        cmmnRuntimeService.createCaseInstanceBuilder().caseDefinitionKey("DEMO-X").start();
                    }
                }""",
        ))
        assertEquals(listOf("java:com.example.Starter" to "case:DEMO-X"), r.edges("references"))
    }

    @Test
    fun aSignalNameIsNoModelKey() {
        val r = extract(mapOf(
            "processes/x.bpmn" to process,
            "src/main/java/com/example/Signaller.java" to """
                package com.example;
                public class Signaller {
                    void fire(Object runtimeService) { runtimeService.signalEventReceived("DEMO-X"); }
                }""",
        ))
        val refs = r.graph("edges").filter { it["rel"] == "references" }
        assertTrue("a signal name equal to a process key is at most a guess: $refs", refs.all { it["suspect"] == true })
    }

    @Test
    fun anActionsBotIsLinkedFromTheActionAndNeverToAModel() {
        val r = extract(mapOf(
            "processes/x.bpmn" to process,
            "forms/f.form" to """{"metadata":{"key":"DEMO-F","name":"F","modelType":"form"},"rows":[]}""",
            "actions/a.action" to """{"key":"DEMO-X","name":"Act","botKey":"DEMO-bot"}""",
            "actions/b.action" to """{"key":"DEMO-B","name":"Act B","botKey":"DEMO-F"}""",
        ))
        assertEquals(
            setOf("action:DEMO-X" to "bot:DEMO-bot", "action:DEMO-B" to "bot:DEMO-F"),
            r.edges("bot").toSet(),
        )
    }

    @Test
    fun aFormFileHoldingAPageCreditsThePage() {
        val r = extract(mapOf(
            "cases/x.cmmn" to caseXml(),
            "forms/x.form" to """{"metadata":{"key":"DEMO-X","name":"Landing","modelType":"page"},
                "rows":[[{"id":"welcome","type":"text","label":"W","value":"{{pageVar}}"}]]}""",
            "apps/a.app" to """{"key":"DEMO-APP","name":"App","pageModels":[{"key":"DEMO-X"}]}""",
        ))
        assertEquals(listOf("page:DEMO-X"), r.node("binding:{{pageVar}}")["usedBy"])
        assertTrue(r.edges("contains").contains("app:DEMO-APP" to "page:DEMO-X"))
        assertFalse(r.edges("contains").contains("app:DEMO-APP" to "case:DEMO-X"))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun aScopeThatReadsEveryVariableSilencesItsOwnVariables() {
        val scriptTask = { id: String, script: String ->
            """<task id="$id" name="$id" flowable:type="script" flowable:scriptFormat="groovy"><extensionElements>
               <flowable:field name="script"><flowable:string>$script</flowable:string></flowable:field></extensionElements></task>"""
        }
        val r = extract(mapOf(
            "processes/x.bpmn" to process,
            "cases/x.cmmn" to caseXml(
                """<planItem id="pi1" definitionRef="w"/><planItem id="pi2" definitionRef="r"/>""" +
                    scriptTask("w", "caseInstance.setVariable('dumpMe', 1)") +
                    scriptTask("r", "println(caseInstance.getVariables())"),
            ),
        ))
        val unused = (r["findings"] as List<Map<String, Any?>>).filter { it["check"] == "unusedVars" }
        assertTrue("the case reads its whole scope, so dumpMe is read: $unused", unused.none { it["node"] == "variable:dumpMe" })
    }
}
