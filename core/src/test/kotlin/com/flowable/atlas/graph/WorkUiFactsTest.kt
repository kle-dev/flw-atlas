package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The model facts that say what a user meets in Flowable Work's UI, outside the case plan
 * ([CmmnRuntimeFactsTest]): which app is a sidebar entry and under which route, what a page is called
 * there, what a field's value has to look like, which panels hide or disable it, whether the form's
 * outcomes wait for it at all, who a task is offered to, and which tasks only exist once an event fires.
 */
class WorkUiFactsTest {

    companion object {
        private lateinit var dir: File
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dir = Files.createTempDirectory("atlas-work-ui-test").toFile()
            File(dir, "apps").mkdirs()
            File(dir, "apps/claims.app").writeText(
                """{"key": "DEMO-claims", "name": "Claims", "flowApp": true, "url": "claims", "order": 3,
                    "pageModels": [
                      {"key": "DEMO-inbox", "name": "Inbox page", "label": "Inbox", "url": "inbox"},
                      {"key": "DEMO-upload", "label": "Upload", "accessPermissions": "DEMO-admins"}
                    ]}""")
            File(dir, "apps/backoffice.app").writeText("""{"key": "DEMO-backoffice", "name": "Back office", "flowApp": false, "url": null}""")
            File(dir, "forms").mkdirs()
            File(dir, "forms/claim.form").writeText(
                """{"metadata": {"key": "claimForm", "name": "Claim", "flowableDesignVersion": 3110},
                    "outcomes": [{"value": "submit", "label": "Submit", "navigationUrl": "#/claims/inbox"}],
                    "defaultDateFormat": "dd.MM.yyyy",
                    "rows": [
                      {"cols": [{"id": "policyNumber", "type": "text", "label": "Policy number", "isRequired": true,
                                 "validationRegExp": "[A-Z]{2}-[0-9]{6}",
                                 "extraSettings": {"minLength": 9, "maxLength": 9}}]},
                      {"cols": [{"id": "amount", "type": "number", "label": "Amount", "value": "{{amount}}",
                                 "extraSettings": {"min": 1, "max": 10000.5},
                                 "customValidations": [{"expression": "{{amount < limit}}", "errorMsg": "Above your limit"}]}]},
                      {"cols": [{"id": "incidentDate", "type": "date", "label": "Date of incident",
                                 "extraSettings": {"format": "dd.MM.yyyy", "maxDate": "today"}}]},
                      {"cols": [{"id": "detailsPanel", "type": "panel", "visible": "{{showDetails}}",
                                 "extraSettings": {"layoutDefinition": {"rows": [
                                   {"cols": [{"id": "innerAmount", "type": "number", "label": "Inner amount", "isRequired": true}]},
                                   {"cols": [{"id": "lockedPanel", "type": "panel", "enabled": false,
                                              "extraSettings": {"layoutDefinition": {"rows": [
                                                {"cols": [{"id": "deepField", "type": "text", "label": "Deep", "isRequired": true}]}
                                              ]}}}]}
                                 ]}}}]},
                      {"cols": [{"id": "claimCount", "type": "number", "isRequired": true}]},
                      {"cols": [{"id": "boundNoLabel", "type": "text", "value": "{{note}}"}]},
                      {"cols": [{"id": "htmlNoLabel", "type": "htmlComponent", "value": "{{summary}}"}]}
                    ]}""")
            File(dir, "forms/legacy.form").writeText(
                """{"metadata": {"key": "legacyForm", "name": "Legacy"}, "doNotCreateOutcomeVariable": true,
                    "outcomes": [{"value": "ok", "label": "OK"}], "rows": []}""")
            File(dir, "processes").mkdirs()
            File(dir, "processes/claim.bpmn20.xml").writeText(
                """<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn">
                     <process id="claimProcess" name="Claim process" flowable:candidateStarterUsers="DEMO-alice">
                       <startEvent id="start"/>
                       <userTask id="review" name="Review" flowable:candidateUsers="DEMO-bob">
                         <multiInstanceLoopCharacteristics isSequential="false" flowable:collection="reviewers">
                           <completionCondition>${'$'}{nrOfCompletedInstances >= 2}</completionCondition>
                         </multiInstanceLoopCharacteristics>
                       </userTask>
                       <sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
                       <subProcess id="escalation" name="Escalation" triggeredByEvent="true">
                         <startEvent id="escStart" isInterrupting="false"><timerEventDefinition><timeDuration>P1D</timeDuration></timerEventDefinition></startEvent>
                         <subProcess id="inner" name="Inner">
                           <userTask id="handle" name="Handle escalation"/>
                         </subProcess>
                       </subProcess>
                     </process>
                   </definitions>""")
            result = Atlas.extract(dir)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun bucket(name: String): List<Map<String, Any?>> = result[name] as List<Map<String, Any?>>

    private fun app(key: String) = bucket("apps").single { it["key"] == key }

    @Suppress("UNCHECKED_CAST")
    private fun fields(form: String): List<Map<String, Any?>> = bucket("forms").single { it["key"] == form }["fields"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun field(form: String, id: String) = fields(form).single { it["id"] == id }

    @Suppress("UNCHECKED_CAST")
    private fun validation(id: String): Map<String, Any?> = field("claimForm", id)["validation"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun process(): Map<String, Any?> = bucket("processes").single()

    @Suppress("UNCHECKED_CAST")
    private fun list(v: Any?): List<Map<String, Any?>> = v as List<Map<String, Any?>>

    @Test
    fun anAppSaysWhetherItIsASidebarEntryAndUnderWhichRoute() {
        val claims = app("DEMO-claims")
        assertEquals(true, claims["flowApp"])
        assertEquals("claims", claims["url"])
        assertEquals(3L, (claims["order"] as Number).toLong())
        val back = app("DEMO-backoffice")
        assertEquals(false, back["flowApp"])
        assertNull("a null url is not a route", back["url"])
    }

    @Test
    fun aPageKeepsTheLabelTheSidebarShowsAndItsRouteSegment() {
        val pages = list(app("DEMO-claims")["pages"])
        assertEquals(mapOf("key" to "DEMO-inbox", "access" to null, "label" to "Inbox", "url" to "inbox"), pages[0])
        assertEquals(mapOf("key" to "DEMO-upload", "access" to "DEMO-admins", "label" to "Upload"), pages[1])
    }

    @Test
    fun aFieldKeepsTheRulesItsValueHasToPass() {
        assertEquals(mapOf("pattern" to "[A-Z]{2}-[0-9]{6}", "minLength" to 9L, "maxLength" to 9L), validation("policyNumber"))
        val amount = validation("amount")
        assertEquals(1L, amount["min"])
        assertEquals(10000.5, amount["max"])
        assertEquals(listOf(mapOf("expression" to "{{amount < limit}}", "errorMsg" to "Above your limit")), amount["custom"])
        assertEquals(mapOf("maxDate" to "today", "format" to "dd.MM.yyyy"), validation("incidentDate"))
        assertFalse("no rules, no key", field("claimForm", "claimCount").containsKey("validation"))
    }

    @Test
    fun aFieldKnowsTheGatedContainersAroundItNearestFirst() {
        val inner = list(field("claimForm", "innerAmount")["containerGates"])
        assertEquals(listOf(mapOf("id" to "detailsPanel", "type" to "panel", "visible" to "{{showDetails}}")), inner)
        val deep = list(field("claimForm", "deepField")["containerGates"])
        assertEquals(listOf("lockedPanel", "detailsPanel"), deep.map { it["id"] })
        assertEquals(false, deep[0]["enabled"])
        assertFalse("outside every gated container", field("claimForm", "policyNumber").containsKey("containerGates"))
    }

    @Test
    fun anUnlabeledInputIsAFieldWhenItIsRequiredOrBoundButADisplayComponentIsNot() {
        assertEquals(true, field("claimForm", "claimCount")["required"])
        assertTrue(fields("claimForm").any { it["id"] == "boundNoLabel" })
        assertFalse(fields("claimForm").any { it["id"] == "htmlNoLabel" })
    }

    @Test
    fun aFormKeepsItsDesignVersionAndWhatItsOutcomesDo() {
        val claim = bucket("forms").single { it["key"] == "claimForm" }
        assertEquals(3110, claim["designVersion"])
        assertEquals("dd.MM.yyyy", claim["defaultDateFormat"])
        assertEquals("#/claims/inbox", list(claim["outcomes"]).single()["navigationUrl"])
        val legacy = bucket("forms").single { it["key"] == "legacyForm" }
        assertNull("hand-written, no version", legacy["designVersion"])
        assertEquals(true, legacy["doNotCreateOutcomeVariable"])
        assertFalse(claim.containsKey("doNotCreateOutcomeVariable"))
    }

    @Test
    fun aUserTaskKeepsItsCandidateUsersAndTheSubProcessItSitsIn() {
        val tasks = list(process()["userTasks"])
        assertEquals("DEMO-bob", tasks.single { it["id"] == "review" }["candidateUsers"])
        assertNull("on the main path", tasks.single { it["id"] == "review" }["parent"])
        assertEquals("inner", tasks.single { it["id"] == "handle" }["parent"])
        val subs = list(process()["subProcesses"])
        assertEquals("escalation", subs.single { it["id"] == "inner" }["parent"])
        assertEquals(true, subs.single { it["id"] == "escalation" }["eventSubProcess"])
    }

    @Test
    fun aProcessKeepsItsCandidateStarterUsersAndAMultiInstanceCompletionCondition() {
        assertEquals("DEMO-alice", process()["candidateStarterUsers"])
        val mi = list(process()["multiInstance"]).single()
        assertEquals("review", mi["activity"])
        assertEquals("\${nrOfCompletedInstances >= 2}", mi["completionCondition"])
    }
}
