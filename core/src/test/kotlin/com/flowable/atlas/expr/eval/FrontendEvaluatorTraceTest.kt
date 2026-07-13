package com.flowable.atlas.expr.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontendEvaluatorTraceTest {

    private fun traced(expr: String, payload: String? = null): TracedEvaluation =
        FrontendExpressionEvaluator.evaluateTraced(expr, payload)

    /** The entry whose source text in [expr] equals [source] (offsets are into the original body). */
    private fun TracedEvaluation.entryFor(expr: String, source: String): TraceEntry {
        val match = entries.filter { expr.substring(it.start, it.end) == source }
        assertTrue("no trace entry for '$source' in '$expr' — entries: $entries", match.isNotEmpty())
        return match.first()
    }

    private fun valueOf(e: TraceEntry): Any? = (e.outcome as TraceOutcome.Value).value

    @Test fun `sub-expressions of a sum are traced with their values`() {
        val expr = "(1+1) + (2+2)"
        val t = traced(expr)
        assertEquals(EvalResult.Ok(6.0), t.result)

        val root = t.entries.single { it.depth == 0 }
        assertEquals(TraceNodeKind.BINARY, root.kind)
        assertEquals(6.0, valueOf(root))

        assertEquals(2.0, valueOf(t.entryFor(expr, "1+1")))
        assertEquals(4.0, valueOf(t.entryFor(expr, "2+2")))
        // literals are traced too (consumers filter by kind)
        assertEquals(TraceNodeKind.LITERAL, t.entryFor(expr, "2").kind)
    }

    @Test fun `short-circuited or branch is not evaluated`() {
        val expr = "true || flw.sum(xs)"
        val t = traced(expr, """{"xs": [1, 2]}""")
        assertEquals(EvalResult.Ok(true), t.result)
        assertEquals(TraceOutcome.NotEvaluated, t.entryFor(expr, "flw.sum(xs)").outcome)
        assertEquals(TraceOutcome.NotEvaluated, t.entryFor(expr, "xs").outcome)
    }

    @Test fun `untaken ternary branch is not evaluated`() {
        val expr = "a ? 'yes' : 'no'"
        val t = traced(expr, """{"a": true}""")
        assertEquals(EvalResult.Ok("yes"), t.result)
        assertEquals("yes", valueOf(t.entryFor(expr, "'yes'")))
        assertEquals(TraceOutcome.NotEvaluated, t.entryFor(expr, "'no'").outcome)
    }

    @Test fun `only the deepest failing node records the error`() {
        val expr = "1 + nope.call()"
        val t = traced(expr)
        // '.call()' on an unresolved root reads as a custom externals object → Unavailable overall
        assertTrue(t.result is EvalResult.Unavailable)
        val failing = t.entries.filter { it.outcome is TraceOutcome.Unavailable }
        assertEquals(1, failing.size)
        assertEquals("nope.call()", expr.substring(failing.single().start, failing.single().end))
        // the untraced result must match the traced one
        assertEquals(t.result, FrontendExpressionEvaluator.evaluate(expr, null))
    }

    @Test fun `genuine error is recorded at the deepest node only`() {
        val expr = "('a').noSuchMethod() + 1"
        val t = traced(expr)
        assertTrue(t.result is EvalResult.Err)
        val errors = t.entries.filter { it.outcome is TraceOutcome.Error }
        assertEquals(1, errors.size)
        assertEquals(TraceNodeKind.CALL, errors.single().kind)
    }

    @Test fun `offsets are shifted back into the wrapped body`() {
        val expr = "{{  1+1 }}"
        val t = traced(expr)
        assertEquals(EvalResult.Ok(2.0), t.result)
        val root = t.entries.single { it.depth == 0 }
        assertEquals("1+1", expr.substring(root.start, root.end))
    }

    @Test fun `arrow bodies are not traced`() {
        val expr = "flw.array.filter(xs, x => x > 1)"
        val t = traced(expr, """{"xs": [1, 2, 3]}""")
        assertEquals(EvalResult.Ok(listOf(2.0, 3.0)), t.result)
        // the call itself has one traced value; nodes inside the arrow body stay NotEvaluated
        assertEquals(TraceOutcome.NotEvaluated, t.entryFor(expr, "x > 1").outcome)
    }

    @Test fun `evaluate delegates to evaluateTraced without collecting`() {
        val t = FrontendExpressionEvaluator.evaluateTraced("1+1", null, collect = false)
        assertEquals(EvalResult.Ok(2.0), t.result)
        assertTrue(t.entries.isEmpty())
    }
}
