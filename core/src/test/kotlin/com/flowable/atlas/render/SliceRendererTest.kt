package com.flowable.atlas.render

import com.flowable.atlas.graph.Atlas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * `--slice`: one node with its context in both directions — the tier between a 3 KB summary and a
 * megabyte graph, which an agent asked to change one process actually needs.
 */
class SliceRendererTest {

    companion object {
        private lateinit var result: Map<String, Any?>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            result = Atlas.extract(File("src/test/resources/miniproject"))
        }
    }

    @Test
    fun aTypedIdRendersTheNodeWithBothDirections() {
        val text = SliceRenderer.render(result, "process:orderProcess")
        assertNotNull(text)
        assertTrue(text!!.startsWith("# `process:orderProcess`"))
        assertTrue("what the node uses", text.contains("## Uses"))
        assertTrue("who uses the node", text.contains("## Used by"))
        assertTrue("the form the process shows is in its context", text.contains("form:orderForm"))
    }

    @Test
    fun theSliceOfAModelListsTheProblemsOfTheExpressionsItHolds() {
        // the finding sits on the expression's node; the process holds it, and its slice must say so
        val text = SliceRenderer.render(result, "process:orderProcess")!!
        assertTrue(text, text.contains("Unclosed '(' in `\${vars:bogus(}`"))
    }

    @Test
    fun aBareKeyMatchesEveryTypeThatCarriesIt() {
        val text = SliceRenderer.render(result, "orderProcess")
        assertNotNull("a bare key must not require the reader to know the type", text)
        assertEquals("one process carries the key, one heading", 1, Regex("^# `", RegexOption.MULTILINE).findAll(text!!).count())
    }

    @Test
    fun anUnknownTargetIsNull() {
        assertNull(SliceRenderer.render(result, "process:doesNotExist"))
        assertNull(SliceRenderer.render(result, "nothing-has-this-key"))
    }

    /** What a reader has in hand when the exact id is not it: the file, another case, the label, the class name. */
    @Test
    fun theFormsAReaderHasInHandMatchToo() {
        for ((target, how) in listOf(
            "ORDERPROCESS" to "case", "processes/order.bpmn" to "file", "./processes/order.bpmn" to "file",
            "order.bpmn" to "file", "Order Process" to "label",
            // the absolute path an agent's file tools hand it
            File("src/test/resources/miniproject/processes/order.bpmn").absolutePath to "file",
        )) {
            val text = SliceRenderer.render(result, target)
            assertNotNull(target, text)
            assertTrue(text, text!!.contains("# `process:orderProcess`"))
            assertTrue("says how it matched: $text", text.startsWith("_`$target` matched by $how:"))
        }
        val bean = SliceRenderer.render(result, "DemoBean")
        assertNotNull("a class by its simple name", bean)
        assertTrue(bean!!.contains("# `java:com.example.DemoBean`"))
        // A file shared by a class and its members renders the class, which lists them.
        val byFile = SliceRenderer.render(result, "src/main/java/com/example/CustomerController.java")!!
        assertEquals(byFile, 1, Regex("^# `", RegexOption.MULTILINE).findAll(byFile).count())
    }

    @Test
    fun aMissSuggestsTheNearestIds() {
        assertTrue(SliceRenderer.suggest(result, "ordrProcess").contains("process:orderProcess"))
        assertEquals("the typo'd type still finds the key", "process:orderProcess",
            SliceRenderer.suggest(result, "bpmn:orderProcess").first())
        assertTrue(SliceRenderer.suggest(result, "zzzzzzzzzzzzzzzzzzzz").isEmpty())
        // Two edits from every short key is no resemblance: `zz` used to suggest `id`, `a` and `zip`.
        assertTrue(SliceRenderer.suggest(result, "zz").toString(), SliceRenderer.suggest(result, "zz").isEmpty())
        assertTrue("nothing to compare, nothing suggested", SliceRenderer.suggest(result, "").isEmpty())
    }

    /**
     * A variable is never an edge's target: who uses it is in its own data. A slice built from edges
     * alone said "Nothing references this node" above the variable's own read and write counts.
     */
    @Test
    fun aVariableNamesTheModelsThatUseItAndWhereItIsSetAndRead() {
        val text = SliceRenderer.render(result, "variable:total")!!
        assertFalse(text, text.contains("Nothing references"))
        assertTrue(text, text.contains("- **used in** ← ") && text.contains("`process:orderProcess`"))
        val setBy = text.substringAfter("## Set by\n").substringBefore("\n\n")
        assertTrue(setBy, setBy.contains("— Calculate total (via `resultVariable`)"))
        val readBy = text.substringAfter("## Read by\n").substringBefore("\n\n")
        assertTrue(readBy, readBy.contains("`decision:orderDecision`") && readBy.contains("(via `dmnInput`)"))
        assertFalse("the sites are on the page, not pointed at", text.contains("`writes`"))
        // A read Atlas only inferred from a script says so.
        val guessed = SliceRenderer.render(result, "variable:courierCode")!!
        assertTrue(guessed, guessed.contains("(via `scriptRead`, guessed)"))
    }

    @Test
    fun anExpressionAndAServiceOperationNameTheModelsThatUseThem() {
        val expr = SliceRenderer.render(result, "expression:\${vars:bogus(}")!!
        assertFalse(expr, expr.contains("Nothing references"))
        assertTrue(expr, expr.contains("- **used in** ← `process:orderProcess`"))
        val op = SliceRenderer.render(result, "serviceOperation:customerService#findAll")!!
        assertTrue(op, op.contains("`case:reviewCase`") && op.contains("`form:orderForm`"))
    }

    @Test
    fun aProcessSliceCarriesItsContentsItsVariablesAndTheExpressionsItEvaluates() {
        val text = SliceRenderer.render(result, "process:orderProcess")!!
        val contents = text.substringAfter("## Contents\n").substringBefore("\n\n")
        assertTrue(text, text.contains("## Contents"))
        assertTrue("the steps, as the overview lists them: $contents", contents.contains("Calculate total"))
        assertTrue("each step names what follows it: $contents", contents.contains(" · next: "))
        assertFalse("the overview's own heading is not repeated", contents.contains("### "))
        val vars = text.substringAfter("## Variables\n").substringBefore("\n\n")
        val total = vars.lines().single { it.startsWith("- `total`") }
        assertTrue(total, total.contains("set by ") && total.contains("Calculate total (resultVariable)"))
        assertTrue(total, total.contains("read by (expression)"))
        assertTrue("its expressions are among what it uses", text.contains("- **uses expression** → ") &&
            text.contains("`expression:\${vars:bogus(}`"))
        assertFalse("the heading already names the key", text.contains("- **key**:"))
        assertFalse("rendered containers are not pointed at again", text.contains("`userTasks`"))
    }

    @Test
    fun aDecisionCaseAndFormSliceCarryTheirRulesPlanAndFields() {
        val decision = SliceRenderer.render(result, "decision:orderDecision")!!
        assertTrue(decision, decision.contains("    - when > 100 → true"))
        val case = SliceRenderer.render(result, "case:reviewCase")!!
        assertTrue(case, case.substringAfter("## Contents").contains("Human task Review order"))
        val form = SliceRenderer.render(result, "form:orderForm")!!
        assertTrue(form, form.substringAfter("## Contents").contains("[number]"))
    }

    /**
     * A case's plan tree from a `.cmmn` file, through the parser. The renderer's own tests hand it maps
     * built by hand, and for a long time those maps held on-parts shaped as no parser writes them: the
     * tests passed while every real case named a plan item by an id none of its lines carries, left out
     * the transition it waits for and printed an empty sentry as a bare `entry`. A plan item's line also
     * says what the case hands to the process it starts.
     */
    @Test
    fun aCaseSliceNamesWhatItsCriteriaWaitForAndWhatItsProcessTasksAreHanded() {
        val tmp = java.nio.file.Files.createTempDirectory("atlas-case-slice").toFile()
        try {
            File(tmp, "cases").mkdirs()
            File(tmp, "cases/intake.cmmn").writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
                             xmlns:flowable="http://flowable.org/cmmn" targetNamespace="http://flowable.org/test">
                  <case id="DEMO-intake" name="Intake">
                    <casePlanModel id="plan" name="Intake plan">
                      <planItem id="planItemcheck" name="Check" definitionRef="check"/>
                      <planItem id="planItemreview" name="Review" definitionRef="review">
                        <entryCriterion id="c1" sentryRef="s1"/>
                      </planItem>
                      <planItem id="planItemarchive" name="Archive" definitionRef="archive">
                        <entryCriterion id="c2" sentryRef="s2"/>
                      </planItem>
                      <sentry id="s1" flowable:triggerMode="onEvent">
                        <planItemOnPart id="on1" sourceRef="planItemcheck">
                          <standardEvent>complete</standardEvent>
                        </planItemOnPart>
                        <ifPart><condition><![CDATA[${'$'}{ready}]]></condition></ifPart>
                      </sentry>
                      <sentry id="s2" flowable:triggerMode="onEvent"/>
                      <humanTask id="check" name="Check"/>
                      <processTask id="review" name="Review" processRef="DEMO-review">
                        <extensionElements>
                          <flowable:in source="orderId" target="reviewedOrder"/>
                          <flowable:in sourceExpression="express" target="priority"/>
                          <flowable:out source="approved" target="approved"/>
                        </extensionElements>
                      </processTask>
                      <humanTask id="archive" name="Archive"/>
                    </casePlanModel>
                  </case>
                </definitions>
                """.trimIndent(),
            )
            val contents = SliceRenderer.render(Atlas.extract(tmp), "case:DEMO-intake")!!.substringAfter("## Contents\n").lines()
            assertEquals(
                "    - Process task Review processRef=`DEMO-review` in(orderId→reviewedOrder) in(\"express\"→priority) " +
                    "out(approved→approved) · entry on complete \"Check\" if `\${ready}` (triggerMode=onEvent)",
                contents.single { it.contains("Process task Review") },
            )
            assertEquals(
                "    - Human task Archive · entry at once (its sentry has no event and no condition)",
                contents.single { it.contains("Human task Archive") },
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * A variable's slice says what each model does with it and what a mapping sets it to. *Used by* listed
     * a model that only ever sets the variable beside the ones that read it, and *Set by … (via
     * `outParameter`)* said that a call sets it and withheld from what — the reader went to the graph for
     * the value, which is what a slice is there to prevent.
     */
    @Test
    fun aVariableSliceSaysWhoSetsAndWhoReadsItAndWhatAMappingAssigns() {
        val total = SliceRenderer.render(result, "variable:total")!!.lines()
        val usedIn = total.single { it.startsWith("- **used in**") }
        assertTrue(usedIn, usedIn.contains("`decision:orderDecision` Order decision (reads)") &&
            usedIn.contains("`java:com.example.DemoBean` DemoBean (sets)") &&
            usedIn.contains("`process:orderProcess` Order Process (sets and reads)"))
        assertTrue(total.joinToString("\n"), total.contains(
            "- `process:orderProcess` Order Process — Fulfil (via `outParameter`) ← `subTotal` of `process:fulfilmentProcess`"))
        val orderId = SliceRenderer.render(result, "variable:orderId")!!.lines()
        assertTrue(orderId.joinToString("\n"), orderId.contains("- `process:orderProcess` Order Process — Fulfil " +
            "(via `inParameterSource`) → copied to `subOrderId` of `process:fulfilmentProcess`, `orderId` of `process:fulfilmentProcess`"))
    }

    /**
     * The report caps a form's fields so it stays a report. A slice is the page of that one form: capped,
     * it sent its reader to the graph for the last few fields.
     */
    @Test
    fun aFormSliceListsEveryFieldWhereTheReportCaps() {
        val tmp = java.nio.file.Files.createTempDirectory("atlas-form-slice").toFile()
        try {
            val fields = (1..45).joinToString(",") { """{"id":"f$it","type":"text","label":"Field $it","value":"{{v$it}}"}""" }
            File(tmp, "wide.form").writeText(
                """{"metadata":{"key":"DEMO-wide","name":"Wide","modelType":"form"},"rows":[[$fields]]}""")
            val extracted = Atlas.extract(tmp)
            val slice = SliceRenderer.render(extracted, "form:DEMO-wide")!!
            assertEquals(45, slice.lines().count { it.startsWith("- `f") && it.contains("[text]") })
            assertFalse(slice, slice.contains("more fields"))
            assertTrue(OverviewRenderer.render(extracted, tmp).contains("(+5 more fields"))
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** No slice may claim nothing references a node whose own data names who uses it. */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun noSliceDeniesAUseItsDataRecords() {
        val nodes = (result["graph"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>
        for (n in nodes) {
            val data = n["data"] as? Map<String, Any?> ?: continue
            val recorded = listOf("usedBy", "reads", "writes", "_uses").any { k ->
                data[k].let { it is Collection<*> && it.isNotEmpty() || it is Map<*, *> && it.isNotEmpty() }
            }
            if (!recorded) continue
            val text = SliceRenderer.render(result, n["id"] as String)!!
            assertFalse("${n["id"]}: $text", text.contains("Nothing references"))
        }
    }
}
