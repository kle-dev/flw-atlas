package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `vars.put("x", …)` into a map handed to the engine writes `x`; and a process that renders a template
 * against its whole variable container reads every variable it holds, so nothing written into it is
 * reported unread. DEMO-* names: the repo is public.
 */
class JavaVariableMapTest {

    private fun unusedVars(processBody: String): List<String> {
        val dir = Files.createTempDirectory("atlas-java-varmap-test").toFile()
        try {
            fun put(rel: String, text: String) = File(dir, rel).apply { parentFile.mkdirs(); writeText(text.trimIndent()) }
            put("models/DEMO-P001.bpmn", """
                <definitions xmlns:flowable="http://flowable.org/bpmn">
                  <process id="DEMO-P001">$processBody</process>
                </definitions>
            """)
            put("src/main/java/com/acme/Starter.java", """
                package com.acme;
                public class Starter {
                    void start() {
                        Map<String, Object> vars = new HashMap<>();
                        vars.put("pendingTasks", tasks);
                        runtimeService.startProcessInstanceByKey("DEMO-P001", vars);
                    }
                }
            """)
            @Suppress("UNCHECKED_CAST")
            val findings = Atlas.extract(dir)["findings"] as List<Map<String, Any?>>
            return findings.filter { it["check"] == "unusedVars" }.map { it["node"].toString() }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aValuePutIntoTheStartMapIsAWrite() {
        assertEquals(listOf("variable:pendingTasks"), unusedVars("<startEvent id=\"s\"/>"))
    }

    @Test
    fun aTemplateRenderedAgainstTheWholeContainerReadsIt() {
        val body = """<serviceTask id="mail" flowable:expression="${'$'}{templateService.createTemplateProcessingBuilder().templateKey('DEMO-T001').variableContainer(execution).process()}"/>"""
        assertEquals(emptyList<String>(), unusedVars(body))
    }
}
