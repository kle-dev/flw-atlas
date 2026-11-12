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

    @Test fun a_bare_event_ref_is_the_name_the_engine_uses() {
        val xml = """
            <definitions xmlns:flowable="http://flowable.org/bpmn" xmlns:tns="urn:demo">
              <message id="m1" name="DEMO-msg"/>
              <signal id="s1" name="DEMO-sig"/>
              <process id="P1">
                <intermediateCatchEvent id="c1"><messageEventDefinition messageRef="m1"/></intermediateCatchEvent>
                <intermediateCatchEvent id="c2"><signalEventDefinition signalRef="tns:s1"/></intermediateCatchEvent>
                <intermediateCatchEvent id="c3"><signalEventDefinition signalRef="DEMO-bare"/></intermediateCatchEvent>
              </process>
            </definitions>
        """.trimIndent()
        val m = ModelMemberExtractor.extract("P1.bpmn20.xml", bytes(xml), ModelType.PROCESS).single().members
        assertEquals("a ref to a declared message is that message's name, not its id", listOf("DEMO-msg"), m.messages)
        assertEquals(listOf("DEMO-sig", "DEMO-bare"), m.signals)
        assertEquals(false, m.dynamicMessages)
        assertEquals(false, m.dynamicSignals)
    }

    @Test fun an_undeclared_prefixed_ref_counts_under_both_spellings() {
        val xml = """
            <definitions xmlns:tns="urn:demo">
              <process id="P1">
                <intermediateCatchEvent id="c1"><signalEventDefinition signalRef="tns:DEMO-sig"/></intermediateCatchEvent>
              </process>
            </definitions>
        """.trimIndent()
        val m = ModelMemberExtractor.extract("P1.bpmn20.xml", bytes(xml), ModelType.PROCESS).single().members
        assertEquals(listOf("tns:DEMO-sig", "DEMO-sig"), m.signals)
    }

    @Test fun a_case_signal_listener_names_a_signal() {
        // runtimeService.signalEventReceived reaches a case's signal event listener as it reaches a process
        val cmmn = """
            <definitions xmlns:flowable="http://flowable.org/cmmn">
              <case id="C1"><casePlanModel id="plan">
                <planItem id="pi1" definitionRef="wait"/>
                <eventListener id="wait" flowable:eventListenerType="signal" flowable:signalRef="DEMO-case-sig"/>
              </casePlanModel></case>
            </definitions>
        """.trimIndent()
        val m = ModelMemberExtractor.extract("C1.cmmn.xml", bytes(cmmn), ModelType.CASE).single().members
        assertEquals(listOf("DEMO-case-sig"), m.signals)
        assertTrue("the listener is still an activity", "wait" in m.activityIds)
    }

    @Test fun a_computed_event_name_marks_the_file_dynamic() {
        val xml = """
            <definitions xmlns:flowable="http://flowable.org/bpmn">
              <message id="m1" name="DEMO-invoice-${'$'}{invoiceNo}"/>
              <process id="P1">
                <intermediateCatchEvent id="c1"><messageEventDefinition messageRef="m1"/></intermediateCatchEvent>
                <intermediateThrowEvent id="t1"><signalEventDefinition flowable:signalExpression="${'$'}{signalName}"/></intermediateThrowEvent>
              </process>
            </definitions>
        """.trimIndent()
        val m = ModelMemberExtractor.extract("P1.bpmn20.xml", bytes(xml), ModelType.PROCESS).single().members
        assertTrue("a name holding an expression is computed at run time", m.dynamicMessages)
        assertTrue("a signalExpression names the signal at run time", m.dynamicSignals)
    }

    @Test fun every_kind_of_activity_a_query_can_name_is_an_activity_id() {
        // The IDE flags an activity id the scoped model does not declare, so a missing element kind is a
        // false warning on a correct query.
        val bpmn = """
            <definitions xmlns:flowable="http://flowable.org/bpmn">
              <process id="P1"><adHocSubProcess id="adhoc"><userTask id="t1"/></adHocSubProcess></process>
            </definitions>
        """.trimIndent()
        val p = ModelMemberExtractor.extract("P1.bpmn20.xml", bytes(bpmn), ModelType.PROCESS).single().members
        assertTrue(p.activityIds.toString(), p.activityIds.containsAll(listOf("adhoc", "t1")))
        val cmmn = """
            <definitions xmlns:flowable="http://flowable.org/cmmn">
              <case id="C1"><casePlanModel id="plan">
                <planItem id="pi1" definitionRef="approve"/><planItem id="pi2" definitionRef="reminder"/>
                <humanTask id="approve"/><timerEventListener id="reminder"/><userEventListener id="cancel"/>
              </casePlanModel></case>
            </definitions>
        """.trimIndent()
        val c = ModelMemberExtractor.extract("C1.cmmn.xml", bytes(cmmn), ModelType.CASE).single().members
        assertTrue(c.activityIds.toString(), c.activityIds.containsAll(listOf("approve", "reminder", "cancel")))
    }
}
