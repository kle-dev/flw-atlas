package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where each variable of the fixture is **written** and where it is **read**.
 *
 * The graph used to record only that a variable occurred somewhere, in a human-readable snippet
 * (`"(declared / mapped)"`, `"(script ≈ read · Stamp order)"`), which cannot answer the one question a
 * reader actually has: does anything consume this? These assertions pin the direction of every flavour
 * of evidence, because the unused-variable verdict is derived from nothing else — if a direction is
 * wrong here, the report accuses the wrong variable.
 */
class VariableDirectionTest {

    private val result: Map<String, Any?> by lazy { Atlas.extract(fixtureDir()) }

    private fun fixtureDir(): File {
        val url = javaClass.classLoader.getResource("miniproject")
            ?: error("miniproject fixture not on the test classpath")
        return File(url.toURI())
    }

    @Suppress("UNCHECKED_CAST")
    private fun variable(name: String): Map<String, Any?> {
        val graph = result["graph"] as Map<String, Any?>
        val nodes = graph["nodes"] as List<Map<String, Any?>>
        return nodes.first { it["id"] == "variable:$name" }["data"] as Map<String, Any?>
    }

    /** `"via@scope"` per site, sorted — the shape of the evidence without its element bookkeeping. */
    @Suppress("UNCHECKED_CAST")
    private fun sites(name: String, key: String): List<String> =
        ((variable(name)[key] as? List<Map<String, Any?>>) ?: emptyList())
            .map { it["via"].toString() + (it["scope"]?.let { s -> "@$s" } ?: "") + (if (it["scopeUnresolved"] == true) "@?" else "") }
            .sorted()

    private fun counts(name: String): Pair<Any?, Any?> =
        variable(name)["writeCount"] to variable(name)["readCount"]

    @Test
    fun aScriptSetIsAWriteAndABareIdentifierIsASuspectedRead() {
        // `execution.setVariable('shippingStamp', …)` in two script tasks, and nothing reads it back.
        assertEquals(listOf("scriptApi", "scriptApi"), sites("shippingStamp", "writes"))
        assertEquals(emptyList<String>(), sites("shippingStamp", "reads"))
        assertEquals(2 to 0, counts("shippingStamp"))

        // `courierCode` appears only as a bare identifier in a script body — a good guess, not a fact,
        // and marked as such so nothing downstream can present it as a declaration.
        assertEquals(listOf("scriptRead"), sites("courierCode", "reads"))
        @Suppress("UNCHECKED_CAST")
        val read = (variable("courierCode")["reads"] as List<Map<String, Any?>>).single()
        assertEquals(true, read["guess"])
        assertNull("a suspected read is never a write", variable("courierCode")["writes"])
    }

    @Test
    fun anInMappingWritesInTheCalleeAndAnOutMappingReadsThere() {
        // `<flowable:in source="orderId" target="subOrderId"/>` on `callSub`: the caller reads `orderId`
        // to hand it over, and `subOrderId` is a variable of the *called* process — which is where a
        // reader has to be looked for. Attributing it to this process would check the wrong model.
        assertTrue("orderId is read to be passed on", sites("orderId", "reads").contains("inParameterSource"))
        assertEquals(listOf("inParameter@process:fulfilmentProcess"), sites("subOrderId", "writes"))
        // `orderId` is also passed through under its own name, so it is written in the callee's scope too
        // while every read of it lives in the caller.
        assertEquals(listOf("inParameter@process:fulfilmentProcess"), sites("orderId", "writes"))

        // `<flowable:out source="subTotal" target="total"/>`: the *source* is read in the callee, the
        // target written here — so the out mapping is a read of the callee's variable, never a write.
        assertTrue(sites("subTotal", "reads").contains("outParameterSource@process:fulfilmentProcess"))
        assertTrue(sites("total", "writes").contains("outParameter"))
    }

    @Test
    fun aDataObjectDeclarationIsAWriteTheEnginePerforms() {
        // `<dataObject>` is the one place BPMN says a process *has* a variable, and the graph was blind to
        // it: the name existed only if something else happened to mention it.
        assertEquals(listOf("dataObject"), sites("orderNotes", "writes"))
        assertEquals(listOf("scriptRead"), sites("orderNotes", "reads"))
    }

    @Test
    fun aResultVariableIsCountedOnceDespiteTwoParsersSeeingIt() {
        // `resultVariableName` is harvested both structurally (with its element) and by the raw-text
        // sweep. Reporting one write twice would inflate every count on the page, so only the structural
        // record survives — the one that knows which task it belongs to.
        assertEquals(
            listOf("javaApi", "outParameter", "resultVariable"),
            sites("total", "writes"),
        )
        @Suppress("UNCHECKED_CAST")
        val resultVar = (variable("total")["writes"] as List<Map<String, Any?>>)
            .single { it["via"] == "resultVariable" }
        assertEquals("calcTask", resultVar["element"])
        assertEquals(3 to 2, counts("total"))
    }

    @Test
    fun aFormFieldIsBothARoadInAndARoadOut() {
        // A Work form field is prefilled from the variable and writes it back on submit, and nothing in
        // the model says which a given field is for. Recording both is honest — and it is why a field
        // alone can never make a variable look unread.
        assertEquals(listOf("formField"), sites("customerRecord", "writes"))
        assertEquals(listOf("formField"), sites("customerRecord", "reads"))
        // `amount` is additionally read by a `{{amount}}` binding elsewhere on the form.
        assertEquals(listOf("binding", "formField"), sites("amount", "reads"))
    }

    @Test
    fun aDecisionReadsItsInputsAndWritesItsOutputs() {
        assertEquals(listOf("dmnOutput"), sites("approved", "writes"))
        assertEquals(emptyList<String>(), sites("approved", "reads"))
        assertTrue("the decision reads `total` as an input", sites("total", "reads").contains("dmnInput"))
    }

    @Test
    fun anActionsPayloadIsReadOnTheWayInAndWrittenOnTheWayOut() {
        // `flw.getInput('customerEmail')` reads what the caller supplied…
        assertEquals(listOf("flwPayload", "scriptApi"), sites("customerEmail", "reads"))
        assertNull(variable("customerEmail")["writes"])
        // …and `flw.setOutput('sent', …)` writes a value the *caller* consumes, which Atlas cannot follow.
        assertEquals(listOf("flwPayload", "scriptApi"), sites("sent", "writes"))
        assertEquals(0, variable("sent")["readCount"])

        // `signalVariableNames`: to pass a variable into the signalled instance the action reads it, so
        // the process's own `setVariable('notified', …)` is not left looking unconsumed.
        assertEquals(listOf("signalVariable"), sites("notified", "reads"))
        assertEquals(listOf("scriptApi"), sites("notified", "writes"))
    }

    @Test
    fun javaVariableAccessKeepsItsVerb() {
        // `DemoBean.run()` does `setVariable("total", 42)` — a write, not merely a mention.
        @Suppress("UNCHECKED_CAST")
        val write = (variable("total")["writes"] as List<Map<String, Any?>>).single { it["via"] == "javaApi" }
        assertEquals("java:com.example.DemoBean", write["model"])
    }

    @Test
    fun aDeclarationWithNoProvableDirectionProducesNoSiteAtAll() {
        // An app variable and a data object's columns are read by the Work UI, a query or a REST client —
        // none of which Atlas parses. They stay in `usages` (so they remain findable) but contribute no
        // direction, which is what keeps them out of a "nothing reads this" verdict.
        for (name in listOf("appVar", "color", "level")) {
            assertEquals("$name should have no proven write", 0 to 0, counts(name))
            assertNull(variable(name)["writes"])
            assertNull(variable(name)["reads"])
        }
    }
}
