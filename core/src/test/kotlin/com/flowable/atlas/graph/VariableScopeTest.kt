package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A name is a variable only where it lives in a process or case scope. Each case below named something
 * else — the forms runtime's scratch, a call's parameter, a template's loop local, a subform's relative
 * path — and made it a variable that tied unrelated models together.
 */
class VariableScopeTest {

    private fun extract(files: Map<String, String>): Map<String, Any?> {
        val dir = Files.createTempDirectory("atlas-variable-scope").toFile()
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

    private fun Map<String, Any?>.usedBy(name: String) = variable(name)?.get("usedBy") as List<*>? ?: emptyList<Any?>()

    private val d = "$"

    @Test
    fun aFormRuntimeRootIsNoVariable() {
        val r = extract(mapOf("forms/f.form" to """{"metadata":{"key":"DEMO-F","name":"F","modelType":"form"},"rows":[[
            {"id":"who","type":"text","label":"Who","value":"{{${d}currentUser.id}} {{${d}searchText}} {{${d}errors}}"}]]}"""))
        for (n in listOf("currentUser", "searchText", "errors")) assertNull(n, r.variable(n))
    }

    @Test
    fun aCallsParameterIsNoVariable() {
        val r = extract(mapOf(
            "services/s.service" to """{"key":"DEMO-S1","name":"Svc","type":"rest","baseUrl":"https://api.example.com",
                "operations":[{"key":"getItem","name":"Get","method":"GET","url":"/items/${d}{itemId}?q=${d}{searchText}",
                  "inputParameters":[{"name":"itemId"},{"name":"searchText"}]}]}""",
            "queries/q.query" to """{"key":"DEMO-Q1","name":"Q","templateContent":"{\"term\": {\"x\": \"{{qParam}}\"}}"}""",
            "agents/a.agent" to """{"key":"DEMO-A1","name":"Agent","type":"task","operations":[{"key":"sum","name":"Sum",
                "userMessage":"Summarize ${d}{inputText}","inputParameters":[{"name":"inputText"}]}]}""",
            "processes/p.bpmn" to """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="DEMO-P1">
                <serviceTask id="svc" flowable:type="service-registry"><extensionElements>
                  <flowable:inputParameter name="itemId" value="${d}{myItem}"/></extensionElements></serviceTask>
                </process></definitions>""",
        ))
        for (n in listOf("itemId", "searchText", "qParam", "inputText")) assertNull(n, r.variable(n))
        // the value handed in is the process's variable
        assertEquals(listOf("process:DEMO-P1"), r.usedBy("myItem"))
    }

    @Test
    fun aComponentWithoutABindingWritesNoVariable() {
        val r = extract(mapOf("forms/f.form" to """{"metadata":{"key":"DEMO-F","name":"F","modelType":"form"},"rows":[[
            {"id":"datatable1","type":"dataTable","label":"T"},{"id":"name","type":"number","label":"Name"}]]}"""))
        assertNull(r.variable("datatable1"))
        assertEquals(listOf("form:DEMO-F"), r.usedBy("name"))
    }

    @Test
    fun aBoundSubformsFieldsAreItsBindingsNotVariables() {
        val r = extract(mapOf(
            "forms/f1.form" to """{"metadata":{"key":"DEMO-F1","name":"Parent","modelType":"form"},"rows":[[
                {"id":"address","type":"subform","label":"Address","value":"{{address}}","extraSettings":{"formRef":{"key":"DEMO-F2"}}}]]}""",
            "forms/f2.form" to """{"metadata":{"key":"DEMO-F2","name":"Address","modelType":"form"},"rows":[[
                {"id":"street","type":"text","label":"Street","value":"{{street}}"}]]}""",
            "processes/p.bpmn" to """<definitions xmlns:flowable="http://flowable.org/bpmn">
                <process id="DEMO-P1"><userTask id="u" flowable:formKey="DEMO-F1"/></process>
                <process id="DEMO-P2"><scriptTask id="s" scriptFormat="groovy"><script>println(street)</script></scriptTask></process>
                </definitions>""",
        ))
        assertFalse("the child writes address.street, not street", r.usedBy("street").contains("form:DEMO-F2"))
        assertTrue(r.usedBy("address").contains("form:DEMO-F1"))
    }

    @Test
    fun anElFunctionsNamespaceIsNoVariable() {
        val r = extract(mapOf("processes/p.bpmn" to """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="DEMO-P1">
            <serviceTask id="t" flowable:expression="${d}{execution.setVariable('o', json:object())}"/></process></definitions>"""))
        assertNull(r.variable("json"))
    }

    @Test
    fun aTemplatesLoopLocalAndBuiltInsAreNoVariables() {
        val r = extract(mapOf("templates/t.tpl" to """{"key":"DEMO-T1","name":"T","type":"text","editorJson":{"templateType":"text",
            "templateVariations":[{"text":"<#list lineItems as li>${d}{li.name}</#list> ${d}{customerName?upper_case} <#assign localTotal = 5>${d}{localTotal}"}]}}"""))
        for (n in listOf("li", "upper_case", "localTotal")) assertNull(n, r.variable(n))
        assertTrue(r.usedBy("customerName").contains("template:DEMO-T1"))
    }

    @Test
    fun javaSpringPlaceholdersAreNoVariables() {
        val r = extract(mapOf("src/main/java/com/example/Cfg.java" to """
            package com.example;
            public class Cfg {
                @Value("${d}{timeout}") private int timeout;
                @Value("#{new Boolean('${d}{demo.x:false}')}") private boolean x;
            }"""))
        assertNull(r.variable("timeout"))
        assertNull(r.variable("demo"))
    }

    @Test
    fun anActionsPayloadKeyIsNoVariable() {
        val r = extract(mapOf("actions/a.action" to """{"key":"DEMO-A","name":"A","botKey":"script-evaluation-bot",
            "config":{"scriptInfo":{"language":"javascript","script":"const c = flw.getInput('customerEmail'); flw.setOutput('sent', true);"}}}"""))
        assertNull(r.variable("customerEmail"))
        assertNull(r.variable("sent"))
    }

    @Test
    fun aScriptsOwnLocalsAreNoVariables() {
        val r = extract(mapOf("processes/p.bpmn" to """<definitions xmlns:flowable="http://flowable.org/bpmn"><process id="DEMO-P1">
            <scriptTask id="s" scriptFormat="javascript"><script>
              var items = execution.getVariable('orders'); var total = 0, count = 0;
              items.forEach((order, idx) => { total = total + order.amount; });
              const { first, second: other } = pair; execution.setVariable('sum', total + count + first + other);
            </script></scriptTask></process></definitions>"""))
        for (n in listOf("order", "idx", "count", "first", "other")) assertNull(n, r.variable(n))
    }
}
