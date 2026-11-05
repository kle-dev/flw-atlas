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
 * The CMMN facts that decide what a user sees and can press in Work, beyond the stage bar
 * ([CmmnStageParsingTest]): what ends the whole case, whether an exit counts as *completes*, when a
 * listener's button exists at all, what a typed event listener waits for, which form an action button
 * opens, and the rules that keep a stage open or let an item repeat.
 *
 * Each of these was missing from the extract, so a reader planning "bring the case to stage N" was told
 * an exit aborted the route when it was the normal way forward, saw a reopen dialog listed as a second
 * start form, and read a variable listener as an automatic step.
 */
class CmmnRuntimeFactsTest {

    companion object {
        private lateinit var dir: File
        private lateinit var case: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        @Suppress("UNCHECKED_CAST")
        fun setUp() {
            dir = Files.createTempDirectory("atlas-cmmn-runtime-test").toFile()
            File(dir, "claim.cmmn").writeText(
                """<definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
                                xmlns:flowable="http://flowable.org/cmmn">
                     <case id="claimCase" name="Claim" flowable:candidateStarterUsers="DEMO-alice">
                       <casePlanModel id="plan" flowable:formKey="claimIntakeForm">
                         <extensionElements>
                           <flowable:workformkey>claimWorkForm</flowable:workformkey>
                           <flowable:custom-view-element customViewKey="DEMO-claimView" permissionGroups="DEMO-team"/>
                         </extensionElements>
                         <planItem id="piSetup" name="0. Setup" definitionRef="stageSetup">
                           <exitCriterion id="exitSetup" sentryRef="sentryReady" flowable:exitEventType="forceComplete"/>
                         </planItem>
                         <planItem id="piReview" name="Review" definitionRef="stageReview">
                           <exitCriterion id="exitReview" sentryRef="sentryReady" flowable:exitType="activeInstances"/>
                         </planItem>
                         <planItem id="piMs" name="Paid" definitionRef="ms"/>
                         <planItem id="piDocs" name="Documents arrived" definitionRef="varListener"/>
                         <planItem id="piFraud" name="Fraud signal" definitionRef="sigListener"/>
                         <planItem id="piReopen" name="Reopen claim" definitionRef="reopen"/>
                         <planItem id="piCasePage" name="Documents" definitionRef="casePage"/>
                         <sentry id="sentryReady"><ifPart><condition>${'$'}{ready}</condition></ifPart></sentry>
                         <sentry id="sentryClose"><ifPart><condition>${'$'}{closed}</condition></ifPart></sentry>
                         <stage id="stageSetup" name="Setup" flowable:autoCompleteCondition="${'$'}{setupDone}">
                           <planItem id="piCheckA" name="Check (first)" definitionRef="check">
                             <entryCriterion id="entryA" sentryRef="sentryReady"/>
                           </planItem>
                           <planItem id="piCheckB" name="Check (second)" definitionRef="check">
                             <itemControl>
                               <extensionElements>
                                 <flowable:parentCompletionRule type="ignoreAfterFirstCompletion"/>
                                 <flowable:completionNeutralRule><condition>${'$'}{neutral}</condition></flowable:completionNeutralRule>
                               </extensionElements>
                               <repetitionRule flowable:maxInstanceCount="2" flowable:collectionVariable="items" flowable:elementVariable="item">
                                 <condition>${'$'}{more}</condition>
                               </repetitionRule>
                             </itemControl>
                           </planItem>
                           <planItem id="piExpert" name="Ask expert" definitionRef="expert">
                             <itemControl>
                               <manualActivationRule>
                                 <extensionElements><flowable:aiActivationType>ai-suggestion</flowable:aiActivationType></extensionElements>
                               </manualActivationRule>
                             </itemControl>
                           </planItem>
                           <planItem id="piSkip" name="Skip" definitionRef="skip"/>
                           <humanTask id="check" name="Check" flowable:candidateUsers="DEMO-bob"/>
                           <humanTask id="expert" name="Ask expert">
                             <extensionElements>
                               <flowable:startformkey>expertRequestForm</flowable:startformkey>
                               <flowable:manual-activation-permission-users>DEMO-carol</flowable:manual-activation-permission-users>
                             </extensionElements>
                           </humanTask>
                           <userEventListener id="skip" name="Skip" flowable:availableCondition="${'$'}{canSkip}">
                             <extensionElements>
                               <flowable:event-listener-permission-users>DEMO-dave</flowable:event-listener-permission-users>
                             </extensionElements>
                           </userEventListener>
                         </stage>
                         <stage id="stageReview" name="Review"/>
                         <milestone id="ms" name="Paid"/>
                         <eventListener id="varListener" name="Documents arrived" flowable:eventType="variable"
                                        flowable:variableName="docsReceived" flowable:variableChangeType="create"/>
                         <eventListener id="sigListener" name="Fraud signal" flowable:eventType="signal" flowable:signalRef="fraud"/>
                         <eventListener id="reopen" name="Reopen claim" flowable:eventType="reactivate">
                           <extensionElements>
                             <flowable:startformkey>claimReopenForm</flowable:startformkey>
                             <flowable:event-listener-timer-expression>P30D</flowable:event-listener-timer-expression>
                           </extensionElements>
                         </eventListener>
                         <task id="casePage" name="Documents" flowable:type="casePage"/>
                         <exitCriterion id="exitCase" sentryRef="sentryClose" flowable:exitEventType="complete"/>
                       </casePlanModel>
                     </case>
                   </definitions>""")
            val result = Atlas.extract(dir)
            case = (result["cases"] as List<Map<String, Any?>>).single()
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dir.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun children(node: Map<String, Any?>): List<Map<String, Any?>> = node["children"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun criteria(node: Map<String, Any?>): List<Map<String, Any?>> = node["criteria"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private val plan: Map<String, Any?> get() = case["planModel"] as Map<String, Any?>

    private val setup: Map<String, Any?> get() = children(plan).single { it["id"] == "stageSetup" }

    @Suppress("UNCHECKED_CAST")
    private fun rules(node: Map<String, Any?>): Map<String, Any?> = node["rules"] as Map<String, Any?>

    @Test
    fun anExitCriterionOnTheCasePlanModelIsKeptOnThePlanModel() {
        @Suppress("UNCHECKED_CAST")
        val exits = plan["exitCriteria"] as List<Map<String, Any?>>
        assertEquals(1, exits.size)
        assertEquals("exitCase", exits[0]["id"])
        assertEquals("sentryClose", exits[0]["sentryRef"])
        assertEquals("exitCriterion", exits[0]["type"])
        assertEquals("complete", exits[0]["exitEventType"])
    }

    @Test
    fun anExitSaysWhetherItCompletesAndWhichInstancesItEnds() {
        val setupExit = criteria(plan).single { it["id"] == "exitSetup" }
        assertEquals("forceComplete", setupExit["exitEventType"])
        val reviewExit = criteria(plan).single { it["id"] == "exitReview" }
        assertEquals("activeInstances", reviewExit["exitType"])
        assertNull("not set in the model, so not on the record", reviewExit["exitEventType"])
    }

    @Test
    fun aCriterionNamesThePlanItemNotJustTheSharedDefinition() {
        val entry = criteria(setup).single { it["id"] == "entryA" }
        assertEquals("piCheckA", entry["planItemId"])
        assertEquals("check", entry["planItemDef"])
        val twoChecks = children(setup).filter { it["id"] == "check" }
        assertEquals(listOf("piCheckA", "piCheckB"), twoChecks.map { it["planItemId"] })
        assertEquals("the plan item's own name, which Work names the instance after",
            listOf("Check (first)", "Check (second)"), twoChecks.map { it["planItemName"] })
    }

    @Test
    fun aStageKeepsItsRuntimeAutoCompleteCondition() {
        assertEquals("\${setupDone}", setup["autoCompleteCondition"])
    }

    @Test
    fun itemControlKeepsRepetitionDetailsAndParentCompletionRules() {
        val second = children(setup).single { it["planItemId"] == "piCheckB" }
        val r = rules(second)
        assertEquals("\${more}", r["repetitionRule"])
        assertEquals("2", r["maxInstanceCount"])
        assertEquals("items", r["collectionVariable"])
        assertEquals("item", r["elementVariable"])
        assertEquals("ignoreAfterFirstCompletion", r["parentCompletionRule"])
        assertEquals("\${neutral}", r["completionNeutralRule"])
        assertTrue("no rules on the first plan item", rules(children(setup).single { it["planItemId"] == "piCheckA" }).isEmpty())
    }

    @Test
    fun aManualItemKeepsItsStartFormUsersAndAiActivation() {
        val expert = children(setup).single { it["id"] == "expert" }
        assertEquals("expertRequestForm", expert["startFormKey"])
        assertEquals("DEMO-carol", expert["actionUsers"])
        assertEquals("ai-suggestion", rules(expert)["aiActivationType"])
        assertEquals(true, rules(expert)["manualActivationRule"])
    }

    @Test
    fun theCaseKnowsItsOwnStartAndWorkFormApartFromItsItemsForms() {
        assertEquals("claimIntakeForm", case["startFormKey"])
        assertEquals("claimWorkForm", case["workFormKey"])
        assertEquals("DEMO-alice", case["candidateStarterUsers"])
    }

    @Test
    fun theCaseKnowsItsCustomViewWhereItsCasePagesAre() {
        assertEquals("DEMO-claimView", case["customViewKey"])
        assertEquals("DEMO-team", case["customViewGroups"])
    }

    @Test
    fun aUserEventListenerKeepsItsAvailabilityConditionAndUsers() {
        val skip = children(setup).single { it["id"] == "skip" }
        assertEquals("\${canSkip}", skip["availableCondition"])
        assertEquals("DEMO-dave", skip["actionUsers"])
    }

    @Test
    fun typedEventListenersSayWhatTheyWaitFor() {
        val variable = children(plan).single { it["id"] == "varListener" }
        assertEquals("variable", variable["listenerType"])
        assertEquals("docsReceived", variable["variableName"])
        assertEquals("create", variable["variableChangeType"])
        val signal = children(plan).single { it["id"] == "sigListener" }
        assertEquals("signal", signal["listenerType"])
        assertEquals("fraud", signal["signalRef"])
        val reopen = children(plan).single { it["id"] == "reopen" }
        assertEquals("reactivate", reopen["listenerType"])
        assertEquals("claimReopenForm", reopen["startFormKey"])
        assertEquals("P30D", reopen["offeredFor"])
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun aGenericEventListenerIsListedWithTheCasesEventListeners() {
        val listeners = case["eventListeners"] as List<Map<String, Any?>>
        assertEquals("signal", listeners.single { it["id"] == "sigListener" }["listenerType"])
        assertEquals("fraud", listeners.single { it["id"] == "sigListener" }["signalRef"])
    }

    @Test
    fun aCasePageTaskSaysItIsOne() {
        val page = children(plan).single { it["id"] == "casePage" }
        assertEquals("casePage", page["taskType"])
    }

    @Test
    fun aHumanTaskKeepsItsCandidateUsers() {
        val check = children(setup).first { it["id"] == "check" }
        assertEquals("DEMO-bob", check["candidateUsers"])
        val expert = children(setup).single { it["id"] == "expert" }
        assertFalse("only when set", expert.containsKey("candidateUsers"))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun stagesAndMilestonesKnowTheirPlaceInTheStageOverviewBeforeAnythingReordersIt() {
        // the engine lists every stage (document order, nested ones included), then every milestone
        assertEquals(0, setup["overviewIndex"])
        assertEquals(1, children(plan).single { it["id"] == "stageReview" }["overviewIndex"])
        assertEquals(2, children(plan).single { it["id"] == "ms" }["overviewIndex"])
        assertEquals(2, (case["milestones"] as List<Map<String, Any?>>).single()["overviewIndex"])
    }
}
