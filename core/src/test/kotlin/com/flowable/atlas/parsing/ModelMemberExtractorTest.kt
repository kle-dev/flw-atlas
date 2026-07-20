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
