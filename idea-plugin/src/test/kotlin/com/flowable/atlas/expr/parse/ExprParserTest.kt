package com.flowable.atlas.expr.parse

import com.flowable.atlas.expr.ExpressionDialect
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ground-truth corpus for the structural parser. The backend cases were verified against the real
 * Flowable JUEL parser (flowable-engine-common `DefaultExpressionManager`); the frontend cases against
 * jsep 0.4.0 as used by `@flowable/forms`. "Function not registered" JUEL errors are NOT syntax errors,
 * so `date:now()` etc. must parse cleanly here (function existence is a separate semantic layer).
 */
class ExprParserTest {

    private fun be(s: String) = ExprParser.parse(s, ExpressionDialect.BACKEND)
    private fun fe(s: String) = ExprParser.parse(s, ExpressionDialect.FRONTEND)

    private fun assertOk(r: ExprParser.ParseResult, src: String) {
        assertNull("expected '$src' to parse, but got: ${r.error?.message}", r.error)
        assertNotNull(r.ast)
    }

    private fun assertErr(r: ExprParser.ParseResult, src: String) {
        assertNotNull("expected '$src' to be a syntax error, but it parsed", r.error)
    }

    @Test
    fun backendValid() {
        listOf(
            "date:now()", "vars:get('order')", "variables:equals('x', 5)",
            "execution.getVariable('a')", "a == b ? c : d", "a == b ? c : (d == 1 ? 2 : 3)",
            "a && (b || c)", "!done", "a >= 5 && b <= 10", "name == 'Kevin'", "a != b",
            "listOf(1,2,3)", "mapOf('k', 'v')", "empty items", "a and b or not done",
            "items[0]", "order.customer.name", "a % 2 == 0", "-a + b", "a.b.c.d",
            "'string with spaces'", "\"double quoted\"", "5.5 + 2",
            "a lt b", "a gt b", "a le 5", "a ge 5", "a eq b", "a ne b", "a div b", "a mod 2",
            "not empty items", "a.b().c", "x -> x + 1", "true && false", "null == a",
        ).forEach { assertOk(be(it), it) }
    }

    @Test
    fun backendInvalid() {
        listOf(
            "a +", "(a == b", "date:now(", "a && || b", "foo(", "a ? b", "a ==", "* b",
            "a b c", "'unterminated", "a.", ")", "[1,2,3]", "{a: 1}", "@foo", "a === b", "a b",
        ).forEach { assertErr(be(it), it) }
    }

    @Test
    fun frontendValid() {
        listOf(
            "flw.sum(items)", "total |> flw.round(2)", "flw.remove.nulls(list)", "a |> flw.sum(b)",
            "!done", "a && !done", "test && (test2 || other)", "5 + 1", "user.name", "user[\"name\"]",
            "age < 16 ? 'child' : 'adult'", "[1,2,3]", "names.join(', ')", "a === b", "a !== b",
            "date2 |> flw.formatTime", "flw.array.filter(items, item => item > 3)",
            "flw.array.reduce(items, 0, [acc, item] => acc + item)", "disabled || invisible",
        ).forEach { assertOk(fe(it), it) }
    }

    @Test
    fun frontendInvalid() {
        listOf(
            "{a: 1}", "5 +", "foo(", "a ? b", "2 ** 3", "a b c", "'unterminated", "user.",
            "x -> x + 1", "[1,2", "flw.round(,)",
        ).forEach { assertErr(fe(it), it) }
    }

    @Test
    fun namespacedCallParsesInBothDialects() {
        // date:now() is syntactically a namespaced call in both dialects; the dialect-fit warning is
        // a separate (token-based) semantic check, not a parse error.
        assertOk(fe("date:now()"), "date:now()")
        assertOk(be("date:now()"), "date:now()")
    }

    @Test
    fun pipeInBackendParsesLeniently() {
        // `|>` is not valid JUEL, but the parser accepts it so the validator can emit a friendly
        // "frontend-only pipe" warning rather than a raw lexical error.
        assertOk(be("a |> b"), "a |> b")
    }

    @Test
    fun errorOffsetsPointIntoSource() {
        val r = be("a && || b")
        assertNotNull(r.error)
        val e = r.error!!
        assertTrue(e.start in 0..("a && || b".length))
    }
}
