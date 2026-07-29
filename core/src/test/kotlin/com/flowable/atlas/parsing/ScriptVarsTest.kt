package com.flowable.atlas.parsing

import com.flowable.atlas.graph.Ctx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [ScriptVars] — what a script body says about the variables it touches.
 *
 * Two contracts are pinned here. First, that the explicit APIs are all recognised, because those names
 * are as reliable as a declaration. Second, and more importantly, the *limits* of the bare-identifier
 * heuristic: locals, class names, method calls, map keys and string contents must never turn into
 * variables. A false variable is worse than a missing one — the variable list is what people trust.
 */
class ScriptVarsTest {

    private fun analyze(script: String, format: String? = "groovy") = ScriptVars.analyze(script, format)

    @Test
    fun explicitApiCallsAreAllRecognised() {
        val use = analyze(
            """
            execution.setVariable('alpha', 1)
            def b = execution.getVariableLocal("beta")
            execution.setTransientVariable('gamma', b)
            flw.setOutput('delta', 2)
            variables.put('epsilon', 3)
            def z = vars['zeta']
            execution.setVariables([eta: 4, 'theta': 5])
            """,
        )
        assertEquals(
            listOf("alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta").sorted(),
            use.api.sorted(),
        )
    }

    @Test
    fun bareIdentifiersAreReadsButLocalsAndTypesAreNot() {
        val use = analyze(
            """
            // orderId and courierCode come from the process scope
            def stamp = "shipped-" + orderId + courierCode
            String other = helperBean.format(stamp)
            for (int i = 0; i < 3; i++) { stamp += i }
            items.each { entry -> println entry }
            execution.setVariable('shippingStamp', stamp)
            """,
        )
        assertEquals(listOf("shippingStamp"), use.api.toList())
        // `items` and `helperBean` are read from the scope too — Atlas cannot tell a bean from a
        // variable here, and the graph filters known bean names out later.
        assertEquals(listOf("courierCode", "helperBean", "items", "orderId"), use.reads.sorted())
    }

    @Test
    fun stringsCommentsAndMapKeysAreNotVariables() {
        val use = analyze(
            """
            /* nothing in here counts: totalAmount */
            // neither does this: customerName
            def msg = 'the invoiceNumber is unknown'
            def payload = [source: "email", status: received]
            """,
        )
        assertTrue(use.api.isEmpty())
        assertFalse(use.reads.any { it in setOf("totalAmount", "customerName", "invoiceNumber", "source", "status") })
        assertTrue(use.reads.contains("received"))          // the *value* side of a map entry is a read
    }

    @Test
    fun groovyStringInterpolationIsRead() {
        val use = analyze("""def greeting = "Hello ${'$'}{customerName}, order ${'$'}{orderId}" """)
        assertTrue(use.reads.containsAll(listOf("customerName", "orderId")))
    }

    @Test
    fun anExpressionLanguageGetsNoBareIdentifierGuessing() {
        // JUEL is an expression, not a script: its identifiers are already harvested by the expression
        // pass, and guessing here would double-report them as script reads.
        val use = analyze("\${orderId > 100}", format = "juel")
        assertTrue(use.reads.isEmpty())
    }

    @Test
    fun scriptTaskVariablesReachTheContextWithTheirElement() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn">
              <process id="p">
                <scriptTask id="stamp" name="Stamp order" scriptFormat="groovy">
                  <script>execution.setVariable('shippingStamp', courierCode)</script>
                </scriptTask>
              </process>
            </definitions>"""
        val ctx = Ctx()
        BackendModelParsers.parseBpmn(xml.toByteArray(), ctx, "p.bpmn")
        val sites = ctx.scriptVarSites.associateBy { it["variable"] }
        assertEquals(setOf("shippingStamp", "courierCode"), sites.keys)
        assertEquals("Stamp order", sites["shippingStamp"]?.get("elementName"))
        assertEquals("stamp", sites["shippingStamp"]?.get("element"))
        assertEquals(true, sites["shippingStamp"]?.get("api"))      // named by setVariable(…)
        assertEquals(false, sites["courierCode"]?.get("api"))       // only read out of the scope
    }

    @Test
    fun listenerScriptVariablesAreAttributedToTheirElement() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn">
              <process id="p">
                <serviceTask id="notify" name="Notify" flowable:delegateExpression="${'$'}{notifierBean}">
                  <extensionElements>
                    <flowable:executionListener event="end">
                      <flowable:script scriptFormat="groovy">execution.setVariable('notified', true)</flowable:script>
                    </flowable:executionListener>
                  </extensionElements>
                </serviceTask>
              </process>
            </definitions>"""
        val ctx = Ctx()
        BackendModelParsers.parseBpmn(xml.toByteArray(), ctx, "p.bpmn")
        val site = ctx.scriptVarSites.single { it["variable"] == "notified" }
        assertEquals("notify", site["element"])
        assertEquals("Notify", site["elementName"])
        assertEquals("executionListener", site["elementType"])
    }
}
