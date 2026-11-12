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
                    "outcomes": [{"value": "submit", "label": "Submit", "navigationUrl": "#/claims/inbox"},
                                 {"value": "park", "label": "Park", "ignoreValidation": true}],
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
            // a guarded embedded sub-process: the guard belongs to the task inside, not to the one after it
            File(dir, "processes/review.bpmn20.xml").writeText(
                """<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn">
                     <process id="DEMO-reviewProcess" name="Review process">
                       <startEvent id="start"/>
                       <exclusiveGateway id="gw" name="Needs review?" default="toArchive"/>
                       <subProcess id="sub" name="Review">
                         <startEvent id="subStart"/>
                         <userTask id="inner" name="Review the request" flowable:candidateGroups="DEMO-reviewers"/>
                         <endEvent id="subEnd"/>
                         <sequenceFlow id="s1" sourceRef="subStart" targetRef="inner"/>
                         <sequenceFlow id="s2" sourceRef="inner" targetRef="subEnd"/>
                       </subProcess>
                       <userTask id="archive" name="Archive" flowable:candidateGroups="DEMO-team"/>
                       <endEvent id="end"/>
                       <sequenceFlow id="f1" sourceRef="start" targetRef="gw"/>
                       <sequenceFlow id="toSub" sourceRef="gw" targetRef="sub">
                         <conditionExpression>${'$'}{needsReview == true}</conditionExpression>
                       </sequenceFlow>
                       <sequenceFlow id="toArchive" sourceRef="gw" targetRef="archive"/>
                       <sequenceFlow id="f2" sourceRef="sub" targetRef="archive"/>
                       <sequenceFlow id="f3" sourceRef="archive" targetRef="end"/>
                     </process>
                   </definitions>""")
            // a case whose task opens the claim form, so the guide has a reason to describe that form
            File(dir, "cases").mkdirs()
            File(dir, "cases/claim.cmmn").writeText(
                """<definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" xmlns:flowable="http://flowable.org/cmmn">
                     <case id="DEMO-claimCase" name="Claim case">
                       <casePlanModel id="plan">
                         <planItem id="piIntake" name="Intake" definitionRef="intake"/>
                         <planItem id="piDecide" name="Decide" definitionRef="decide"/>
                         <stage id="intake" name="Intake" flowable:displayOrder="1">
                           <planItem id="piFill" name="Fill in the claim" definitionRef="fill"/>
                           <humanTask id="fill" name="Fill in the claim" flowable:formKey="claimForm" flowable:candidateGroups="DEMO-team"/>
                         </stage>
                         <stage id="decide" name="Decide" flowable:displayOrder="2"/>
                       </casePlanModel>
                     </case>
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
    private fun process(): Map<String, Any?> = bucket("processes").single { it["key"] == "claimProcess" }

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
    fun aFieldNamesTheNearestGatedContainerAndAContainerTheNextOneOut() {
        assertEquals("detailsPanel", field("claimForm", "innerAmount")["containerGate"])
        assertEquals("lockedPanel", field("claimForm", "deepField")["containerGate"])
        assertFalse("outside every gated container", field("claimForm", "policyNumber").containsKey("containerGate"))
        @Suppress("UNCHECKED_CAST")
        val containers = bucket("forms").single { it["key"] == "claimForm" }["containers"] as Map<String, Map<String, Any?>>
        assertEquals(setOf("detailsPanel", "lockedPanel"), containers.keys)
        assertEquals(mapOf("id" to "detailsPanel", "type" to "panel", "visible" to "{{showDetails}}"), containers["detailsPanel"])
        assertEquals(false, containers.getValue("lockedPanel")["enabled"])
        assertEquals("the next gated container out", "detailsPanel", containers.getValue("lockedPanel")["containerGate"])
    }

    /**
     * A container's gates are stored once per form, not once per field inside it: copied onto every field,
     * they grew with fields × nesting depth × condition length and multiplied the size of graphs with large,
     * nested forms. The containers around a container are stored on the container for the same reason: a
     * field that listed all of them still grew with fields × nesting depth.
     */
    @Test
    fun aGatedContainerIsRecordedOncePerFormNotOncePerField() {
        val tmp = Files.createTempDirectory("atlas-gates-test").toFile()
        try {
            File(tmp, "forms").mkdirs()
            File(tmp, "forms/deep.form").writeText(NestedFormFixture.json(depth = 5))
            @Suppress("UNCHECKED_CAST")
            val form = (Atlas.extract(tmp)["forms"] as List<Map<String, Any?>>).single()
            @Suppress("UNCHECKED_CAST")
            val containers = form["containers"] as Map<String, Map<String, Any?>>
            assertEquals("one record per container", 5, containers.size)
            @Suppress("UNCHECKED_CAST")
            val nearest = (form["fields"] as List<Map<String, Any?>>).mapNotNull { it["containerGate"] }
            assertEquals("every field below the first panel names its panel", 200, nearest.size)
            assertEquals("a field names the nearest container only", "panel4", nearest.last())
            assertEquals("the containers around it are the containers' own chain, outwards",
                listOf("panel4", "panel3", "panel2", "panel1", "panel0"),
                generateSequence("panel4") { containers.getValue(it)["containerGate"] as String? }.toList())
        } finally {
            tmp.deleteRecursively()
        }
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
        assertEquals("#/claims/inbox", list(claim["outcomes"]).single { it["value"] == "submit" }["navigationUrl"])
        assertEquals(true, list(claim["outcomes"]).single { it["value"] == "park" }["ignoreValidation"])
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
    fun anEventKnowsTheSubProcessItSitsIn() {
        val events = list(bucket("processes").single { it["key"] == "DEMO-reviewProcess" }["events"])
        assertEquals("sub", events.single { it["id"] == "subStart" }["parent"])
        assertNull("on the main path", events.single { it["id"] == "start" }["parent"])
    }

    /**
     * The guide read from these parsed models, not from hand-built maps. PlaywrightRendererTest pins each
     * of these shapes with a map it writes itself; this is what fails when a parser key and the renderer
     * stop agreeing, and the hand-built test, still matching its own map, would stay green.
     */
    @Test
    fun theGuideWarnsAboutWhatTheseFactsHide() {
        val guide = com.flowable.atlas.render.PlaywrightRenderer.render(result, dir)
        val hidden = guide.lines().single { it.contains("required but hidden at times") }
        assertTrue(hidden, hidden.contains("`innerAmount` (with `panel` `detailsPanel`, visible if `{{showDetails}}`)"))
        assertFalse("its panel is disabled, so it blocks nothing", hidden.contains("deepField"))
        assertTrue(guide, guide.contains("*Park* `park` (works on an invalid form)"))
        assertTrue(guide, guide.contains("**Stage bar:** Step 1 *Intake* · Step 2 *Decide*"))
        // the guard in front of the sub-process is the inner task's, and the task after it waits for that one
        assertTrue(guide, guide.lines().any { it.startsWith("1. *Review the request*") &&
            it.contains("reached after *Needs review?* if `\${needsReview == true}`") })
        assertTrue(guide, guide.lines().any { it.startsWith("2. *Archive*") && it.contains("or after sub-process *Review*") })
    }

    @Test
    fun aProcessKeepsItsCandidateStarterUsersAndAMultiInstanceCompletionCondition() {
        assertEquals("DEMO-alice", process()["candidateStarterUsers"])
        val mi = list(process()["multiInstance"]).single()
        assertEquals("review", mi["activity"])
        assertEquals("\${nrOfCompletedInstances >= 2}", mi["completionCondition"])
    }
}
