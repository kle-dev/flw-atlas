package com.flowable.atlas.graph

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * What the CMMN parser has to keep for a reader to answer "how does this case reach stage N": the plan
 * item ids a sentry's on-parts name, the event each on-part waits for, the sentry's real condition, and
 * the two attributes that place a stage in Work's stage bar.
 *
 * The model is shaped like a Design export, because that is where each of these went missing: plan item
 * ids differ from definition ids (`planItem` + definition id), and the condition builder writes empty
 * `<flowable:condition>` elements *before* the `ifPart` — the first `condition` element in document order
 * used to be read as the condition, which on every such sentry was nothing.
 */
class CmmnStageParsingTest {

    companion object {
        private lateinit var dir: File
        private lateinit var case: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        @Suppress("UNCHECKED_CAST")
        fun setUp() {
            dir = Files.createTempDirectory("atlas-cmmn-stage-test").toFile()
            File(dir, "claim.cmmn").writeText(
                """<definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
                                xmlns:flowable="http://flowable.org/cmmn"
                                xmlns:design="http://flowable.org/design">
                     <case id="c" name="C">
                       <casePlanModel id="plan">
                         <planItem id="planItemstageA" name="A" definitionRef="stageA"/>
                         <planItem id="planItemstageB" name="B" definitionRef="stageB">
                           <entryCriterion id="critB" sentryRef="sentryB"/>
                         </planItem>
                         <planItem id="planItemms" name="Done" definitionRef="ms"/>
                         <planItem id="planItemuel" name="Skip" definitionRef="uel"/>
                         <sentry id="sentryB" flowable:triggerMode="onEvent">
                           <extensionElements>
                             <flowable:condition group="true" match="any" targetAttribute="condition">
                               <flowable:condition sourceName="ok" operator="equals" targetValue="true"/>
                             </flowable:condition>
                             <design:stencilid><![CDATA[EntryCriterion]]></design:stencilid>
                           </extensionElements>
                           <planItemOnPart id="onA" sourceRef="planItemstageA"><standardEvent>complete</standardEvent></planItemOnPart>
                           <ifPart><condition><![CDATA[${'$'}{ok == true}]]></condition></ifPart>
                         </sentry>
                         <stage id="stageA" name="A" autoComplete="true" flowable:displayOrder="10">
                           <planItem id="planItemhtA" name="Do A" definitionRef="htA">
                             <itemControl><manualActivationRule/></itemControl>
                           </planItem>
                           <humanTask id="htA" name="Do A">
                             <extensionElements>
                               <flowable:manual-activation-name>Start A now</flowable:manual-activation-name>
                               <flowable:manual-activation-permission-groups>clerks</flowable:manual-activation-permission-groups>
                             </extensionElements>
                           </humanTask>
                         </stage>
                         <stage id="stageB" name="B" flowable:displayOrder="20"/>
                         <milestone id="ms" name="Done" flowable:includeInStageOverview="false"/>
                         <userEventListener id="uel" name="Skip">
                           <extensionElements>
                             <flowable:startformkey>skipForm</flowable:startformkey>
                           </extensionElements>
                         </userEventListener>
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
    private val plan: Map<String, Any?> get() = case["planModel"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private val sentry: Map<String, Any?> get() = (case["sentries"] as List<Map<String, Any?>>).single()

    @Test
    fun theSentryConditionIsTheIfPartNotTheConditionBuilder() {
        assertEquals("\${ok == true}", sentry["condition"])
    }

    @Test
    fun theSentryKeepsItsTriggerModeAndTheEventEachOnPartWaitsFor() {
        assertEquals("onEvent", sentry["triggerMode"])
        assertEquals(listOf(mapOf("source" to "planItemstageA", "event" to "complete")), sentry["events"])
        assertEquals(listOf("planItemstageA"), sentry["onParts"])
    }

    @Test
    fun aPlanItemKeepsItsOwnIdWhenItDiffersFromTheDefinition() {
        val a = children(plan).single { it["id"] == "stageA" }
        assertEquals("planItemstageA", a["planItemId"])
    }

    @Test
    fun aStageKeepsItsPlaceInTheStageBar() {
        val a = children(plan).single { it["id"] == "stageA" }
        assertEquals("10", a["displayOrder"])
        assertNull("not set in the model, so not on the record", a["includeInStageOverview"])
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun aMilestoneKeepsWhetherItShowsInTheStageBar() {
        val ms = (case["milestones"] as List<Map<String, Any?>>).single()
        assertEquals("false", ms["includeInStageOverview"])
    }

    @Test
    fun theButtonWorkMakesOfAManualItemCarriesTheModelsNameAndGroups() {
        val a = children(plan).single { it["id"] == "stageA" }
        val task = children(a).single()
        assertEquals("Start A now", task["actionName"])
        assertEquals("clerks", task["actionGroups"])
    }

    @Test
    fun aUserEventListenerKnowsTheFormItsButtonOpens() {
        val uel = children(plan).single { it["type"] == "userEventListener" }
        assertEquals("skipForm", uel["startFormKey"])
    }
}
