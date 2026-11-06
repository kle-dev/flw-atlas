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
