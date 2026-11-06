package com.flowable.atlas.graph

import com.flowable.atlas.parsing.ModelParsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Readers and writers the variable graph did not see: Java receiving a decision's outputs, a query filtering
 * on a variable by name, a multi-instance aggregation. Each left a variable reported as written but never
 * read, with the advice to delete a write the project depends on.
 */
class VariableReadersTest {

    private val d = "$"

    private fun extract(files: Map<String, String>): Map<String, Any?> {
        val dir = Files.createTempDirectory("atlas-variable-readers").toFile()
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
    private fun Map<String, Any?>.sites(name: String, dir: String): List<String> =
        ((variable(name)?.get(dir) as? List<Map<String, Any?>>).orEmpty()).map { "${it["via"]}@${it["model"]}" }.sorted()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.unused(): List<String> =
        (this["findings"] as List<Map<String, Any?>>).filter { it["check"] == "unusedVars" }.map { it["node"].toString() }

    private fun process(id: String, body: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://example.com">
          <process id="$id" name="$id">$body</process>
        </definitions>"""

    private fun initVars(vararg names: String) =
        """<serviceTask id="init" name="Initialize" flowable:delegateExpression="${d}{initVariablesService}"><extensionElements>""" +
            names.joinToString("") { """<flowable:variableMapping name="$it" valueType="string" value="x"/>""" } +
            "</extensionElements></serviceTask>"

    @Test
    fun aDecisionOnlyJavaRunsWritesNoVariable() {
        val r = extract(mapOf(
            "table.dmn" to """<?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="http://www.flowable.org/dmn">
                  <decision id="DEMO-table" name="Risk"><decisionTable id="dt" hitPolicy="FIRST">
                    <input id="i1" label="Amount"><inputExpression id="ie1" typeRef="number"><text>amount</text></inputExpression></input>
                    <output id="o1" label="Risk" name="risk" typeRef="string"/>
                  </decisionTable></decision>
                </definitions>""",
            "src/main/java/com/example/RiskCalculator.java" to """
                package com.example;
                import java.util.Map;
                import org.springframework.stereotype.Service;
                @Service
                public class RiskCalculator {
                    public String risk(org.flowable.dmn.api.DmnDecisionService rules, long amount) {
                        Map<String, Object> result = rules.createExecuteDecisionBuilder().decisionKey("DEMO-table")
                            .variable("amount", amount).executeWithSingleResult();
                        return (String) result.get("risk");
                    }
                }""",
        ))
        assertEquals(listOf("dmnOutput@decision:DEMO-table"), r.sites("risk", "writes"))
        assertTrue(r.unused().isEmpty())
        assertEquals("declined, not cleared: its reader is Java", true, r.variable("risk")?.get("readsUnknown"))
    }

    @Test
    fun aQueryFilteringOnAVariableReadsIt() {
        val filter = """<#ftl output_format="JSON">{"bool":{"must":[{"nested":{"path":"variables","query":{"bool":{"must":[
            {"term":{"variables.name":"demoFlag"}},{"terms":{"variables.name.keyword":["demoOther","demoThird"]}},
            {"term":{"variables.name":"${d}{param}"}},{"match":{"variables.textValue":"DEMO"}}]}}}}]}}"""
        val r = extract(mapOf(
            "p.bpmn20.xml" to process("DEMO-q", """<startEvent id="s"/>${initVars("demoFlag", "demoOther", "demoThird", "demoShown", "demoCustomer")}"""),
            "q.query" to """{"key":"DEMO-QM001","name":"Tasks","type":"template-query","sourceIndex":"tasks",
                "templateFilter":${MiniJsonQuote.of(filter)},"columns":[{"name":"c","label":"Shown","variableName":"demoShown"},
                {"name":"n","label":"Customer","variableName":"demoCustomer.name"}]}""",
        ))
        assertTrue(r.unused().toString(), r.unused().isEmpty())
        for (n in listOf("demoFlag", "demoOther", "demoThird")) assertEquals(n, listOf("queryFilter@query:DEMO-QM001"), r.sites(n, "reads"))
        assertEquals(listOf("queryColumn@query:DEMO-QM001"), r.sites("demoShown", "reads"))
        // a column showing a path reads the variable at its root
        assertEquals(listOf("queryColumn@query:DEMO-QM001"), r.sites("demoCustomer", "reads"))
        assertNull("a `${d}{…}` value is the query's request parameter", r.variable("param"))
    }

    @Test
    fun theFilterReaderTakesOnlyLiteralNames() {
        assertEquals(setOf("a", "b"), ModelParsers.queryFilterVariables("""{"term":{"variables.name":"a"}} {"terms":{"variables.name":["b","${d}{c}"]}}"""))
        // a template stored as a JSON string inside the model escapes its quotes once more
        assertEquals(setOf("x"), ModelParsers.queryFilterVariables("""{\"term\":{\"variables.name\":\"x\"}}"""))
        assertEquals(emptySet<String>(), ModelParsers.queryFilterVariables("""{"term":{"variables.name":"${d}{param}"}}"""))
    }

    @Test
    fun aVariableAggregationReadsItsSourcesAndWritesItsTarget() {
        val r = extract(mapOf(
            "p.bpmn20.xml" to process("DEMO-agg", """
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="each"/>
                <serviceTask id="each" name="Map each item" flowable:delegateExpression="${d}{initVariablesService}">
                  <extensionElements><flowable:variableMapping name="itemId" valueExpression="${d}{items[loopCounter].id}"/></extensionElements>
                  <multiInstanceLoopCharacteristics isSequential="false" flowable:collection="items"><extensionElements>
                    <flowable:variableAggregation target="itemIds" createOverviewVariable="true">
                      <variable source="itemId" target="id"></variable>
                    </flowable:variableAggregation></extensionElements></multiInstanceLoopCharacteristics>
                </serviceTask>
                <sequenceFlow id="f2" sourceRef="each" targetRef="u"/>
                <userTask id="u" name="Review" flowable:assignee="${d}{itemIds.size() > 0 ? 'a' : 'b'}"/>"""),
            "c.cmmn" to """<?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" xmlns:flowable="http://flowable.org/cmmn" targetNamespace="http://example.com">
                  <case id="DEMO-rep" name="Rep"><casePlanModel id="pm" name="Plan">
                    <planItem id="pi" definitionRef="t"><itemControl>
                      <repetitionRule flowable:collectionVariable="rows" flowable:elementVariable="row"><extensionElements>
                        <flowable:variableAggregation target="rowResults"><variable source="rowResult" target="value"/></flowable:variableAggregation>
                      </extensionElements></repetitionRule></itemControl></planItem>
                    <humanTask id="t" name="Check" flowable:assignee="${d}{rowResults} ${d}{rowResult}"/>
                  </casePlanModel></case>
                </definitions>""",
        ))
        assertTrue(r.sites("itemId", "reads").contains("variableAggregationSource@process:DEMO-agg"))
        assertEquals(listOf("variableAggregation@process:DEMO-agg"), r.sites("itemIds", "writes"))
        assertTrue(r.unused().toString(), "variable:itemId" !in r.unused())
        // the inner `target` is a field of each collected item, no variable
        assertNull(r.variable("id"))
        assertTrue(r.sites("rowResult", "reads").contains("variableAggregationSource@case:DEMO-rep"))
        assertEquals(listOf("variableAggregation@case:DEMO-rep"), r.sites("rowResults", "writes"))
        assertNull(r.variable("value"))
    }
}

/** A string as a JSON string literal, for templates written into a model's JSON. */
private object MiniJsonQuote {
    fun of(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            else -> append(c)
        }
        append('"')
    }
}
