package com.flowable.atlas.expr.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontendEvaluatorTest {

    private fun value(expr: String, payload: String? = null): Any? {
        val r = FrontendExpressionEvaluator.evaluate(expr, payload)
        assertTrue("expected success for '$expr' but got: ${(r as? EvalResult.Err)?.message}", r is EvalResult.Ok)
        return (r as EvalResult.Ok).value
    }

    private fun error(expr: String, payload: String? = null): String {
        val r = FrontendExpressionEvaluator.evaluate(expr, payload)
        assertTrue("expected error for '$expr' but got success", r is EvalResult.Err)
        return (r as EvalResult.Err).message
    }

    @Test fun arithmetic() {
        assertEquals(6.0, value("5 + 1"))
        assertEquals("ab", value("'a' + 'b'"))
        assertEquals("5", value("5 + null"))          // + coalesces null to ""
        assertEquals(6.0, value("2 * 3"))
    }

    @Test fun payloadIdentifiersAndMembers() {
        assertEquals("Kevin", value("user.name", """{"user":{"name":"Kevin"}}"""))
        assertEquals("Kevin", value("user[\"name\"]", """{"user":{"name":"Kevin"}}"""))
        assertNull(value("missing.foo", "{}"))        // unknown identifier → null, not an error
        assertEquals(3.0, value("items.length", """{"items":[1,2,3]}"""))
    }

    @Test fun ternaryAndLogical() {
        assertEquals("child", value("age < 16 ? 'child' : 'adult'", """{"age":10}"""))
        assertEquals(true, value("disabled || invisible", """{"disabled":false,"invisible":true}"""))
        assertEquals(true, value("!done", """{"done":false}"""))
    }

    @Test fun flwAggregationAndCollection() {
        assertEquals(6.0, value("flw.sum(items)", """{"items":[1,2,3]}"""))
        assertEquals(listOf(1.0, 2.0), value("flw.mapAttr(order, 'amount')", """{"order":[{"amount":1},{"amount":2}]}"""))
        assertEquals(3.0, value("flw.sum(flw.mapAttr(order, 'amount'))", """{"order":[{"amount":1},{"amount":2}]}"""))
        assertEquals(25.23, value("flw.round(25.2345, 2)"))
        assertEquals("a, b", value("names.join(', ')", """{"names":["a","b"]}"""))
    }

    @Test fun flwExists() {
        assertEquals(false, value("flw.exists(x)", """{"x":""}"""))
        assertEquals(true, value("flw.exists(x)", """{"x":0}"""))
        assertEquals(false, value("flw.notExists(x)", """{"x":0}"""))
    }

    @Test fun flwArrayHigherOrder() {
        assertEquals(listOf(3.0, 4.0), value("flw.array.filter(items, item => item > 2)", """{"items":[1,2,3,4]}"""))
        assertEquals(6.0, value("flw.array.reduce(items, 0, [acc, item] => acc + item)", """{"items":[1,2,3]}"""))
        assertEquals(true, value("flw.array.any(items, x => x > 2)", """{"items":[1,2,3]}"""))
    }

    @Test fun pipeWithFunctionReference() {
        assertEquals(3.0, value("total |> flw.round", """{"total":2.7}"""))
    }

    @Test fun pipeWithCallIsAHelpfulError() {
        // `x |> flw.round(2)` is the classic mistake — the real form silently yields undefined.
        val msg = error("total |> flw.round(2)", """{"total":2.7}""")
        assertTrue(msg.contains("function reference"))
    }

    @Test fun syntaxErrorIsReported() {
        assertTrue(error("5 +").contains("Syntax error"))
    }

    @Test fun invalidPayloadIsReported() {
        assertTrue(error("x", "{not json").contains("Invalid payload JSON"))
    }

    @Test fun unsupportedEnvFunctionIsHonest() {
        assertTrue(error("flw.timeZone()").contains("not available in the payload preview"))
    }

    @Test fun jsonRoundTrip() {
        assertEquals("""{"a":1}""", value("flw.JSON.stringify(x)", """{"x":{"a":1}}"""))
        assertEquals(1.0, value("flw.JSON.parse(s).a", """{"s":"{\"a\":1}"}"""))
    }
}
