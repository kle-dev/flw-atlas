package com.flowable.atlas.parsing

import com.flowable.atlas.model.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the StAX-based member extraction (pure, no IDE fixture needed). */
class ModelMemberExtractorTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test fun bpmn_members_are_extracted() {
        val xml = """
            <definitions xmlns:flowable="http://flowable.org/bpmn">
              <message id="m1" name="orderPlaced"/>
              <signal id="s1" name="cancelSignal"/>
              <process id="P1" name="Proc">
                <dataObject id="d1" name="amount"/>
                <startEvent id="start"/>
                <userTask id="review">
                  <extensionElements><flowable:formProperty id="approved"/></extensionElements>
                </userTask>
                <serviceTask id="calc"/>
              </process>
            </definitions>
        """.trimIndent()
        val models = ModelMemberExtractor.extract("P1.bpmn20.xml", bytes(xml), ModelType.PROCESS)
        assertEquals(1, models.size)
        val m = models.single()
        assertEquals("P1", m.key)
        assertTrue("messages: ${m.members.messages}", m.members.messages.contains("orderPlaced"))
        assertTrue("signals: ${m.members.signals}", m.members.signals.contains("cancelSignal"))
        assertTrue("variables: ${m.members.variables}", m.members.variables.contains("amount"))
        assertTrue("formProperty variable: ${m.members.variables}", m.members.variables.contains("approved"))
        assertTrue("userTasks: ${m.members.userTaskIds}", m.members.userTaskIds.contains("review"))
        assertTrue("activityIds: ${m.members.activityIds}", m.members.activityIds.containsAll(listOf("start", "review", "calc")))
    }

    /** `adHocSubProcess` is the engine's spelling; under `adhocSubProcess` the IDE never offered its id. */
    @Test fun an_ad_hoc_sub_process_is_an_activity() {
        val xml = """
            <definitions>
              <process id="P1">
                <adHocSubProcess id="adhoc1"><userTask id="review"/></adHocSubProcess>
              </process>
            </definitions>
        """.trimIndent()
        val m = ModelMemberExtractor.extract("P1.bpmn20.xml", bytes(xml), ModelType.PROCESS).single()
        assertTrue("activityIds: ${m.members.activityIds}", m.members.activityIds.containsAll(listOf("adhoc1", "review")))
    }

    @Test fun dmn_decision_variables_are_extracted() {
        val xml = """
            <definitions>
              <decision id="D1" name="Dec">
                <decisionTable>
                  <input label="Age"><inputExpression id="e1"><text>age</text></inputExpression></input>
                  <output name="result"/>
                </decisionTable>
              </decision>
            </definitions>
        """.trimIndent()
        val m = ModelMemberExtractor.extract("D1.dmn", bytes(xml), ModelType.DECISION).single()
        assertEquals("D1", m.key)
        assertTrue("decision vars: ${m.members.decisionVariables}", m.members.decisionVariables.contains("age"))
        assertTrue("decision vars: ${m.members.decisionVariables}", m.members.decisionVariables.contains("result"))
    }

    @Test fun a_forms_variables_are_the_names_it_binds_not_its_component_ids() {
        // Design names components `text1`, `date1`: the variable is what the `{{…}}` value binds. An unbound
        // input's id is its variable, a result button stores into its binding, the form runtime's own
        // `$temp` is none, and the outcome variable is one too.
        val d = "${'$'}"
        val json = """{"metadata":{"key":"DEMO-F1","name":"F"},"outcomevariablename":"reviewOutcome","rows":[[
            {"id":"text1","type":"text","label":"Name","value":"{{customerName}}"},
            {"id":"date1","type":"date","label":"Born","value":"{{customer.birthDate}}"},
            {"id":"amount","type":"number","label":"Amount"},
            {"id":"scratch1","type":"text","label":"Scratch","value":"{{${d}temp.x}}"},
            {"id":"listed","type":"select","label":"Listed","value":"{{item.code}}"},
            {"id":"calc1","type":"scriptButton","extraSettings":{"text":"Estimate"},"value":"{{estimatedPayout}}"},
            {"id":"panel1","type":"panel","label":"P","value":"{{customerName}}"}]]}"""
        for ((file, type) in listOf("f.form" to ModelType.FORM, "p.page" to ModelType.PAGE)) {
            val m = ModelMemberExtractor.extract(file, bytes(json), type).single().members
            assertEquals(listOf("customerName", "customer", "amount", "estimatedPayout", "reviewOutcome"), m.variables)
            assertTrue("navigation keeps the ids: ${m.formFields}", m.formFields.containsAll(listOf("text1", "date1", "amount")))
        }
    }

    @Test fun action_botKey_is_extracted() {
        val json = """{ "key": "greetAction", "name": "Greet", "botKey": "greeter-bot" }"""
        val m = ModelMemberExtractor.extract("greet.action", bytes(json), ModelType.ACTION).single()
        assertEquals("greetAction", m.key)
        assertEquals("greeter-bot", m.members.botKey)
    }

    @Test fun action_without_botKey_has_null() {
        val json = """{ "key": "greetAction", "name": "Greet" }"""
        val m = ModelMemberExtractor.extract("greet.action", bytes(json), ModelType.ACTION).single()
        assertEquals(null, m.members.botKey)
    }

    @Test fun multiple_processes_in_one_file() {
        val xml = """
            <definitions>
              <process id="A"><userTask id="ta"/></process>
              <process id="B"><userTask id="tb"/></process>
            </definitions>
        """.trimIndent()
        val models = ModelMemberExtractor.extract("multi.bpmn20.xml", bytes(xml), ModelType.PROCESS)
        assertEquals(setOf("A", "B"), models.map { it.key }.toSet())
        assertEquals(listOf("ta"), models.first { it.key == "A" }.members.userTaskIds)
        assertEquals(listOf("tb"), models.first { it.key == "B" }.members.userTaskIds)
    }
}
