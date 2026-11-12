package com.flowable.atlas.expr.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playground's ` = value` hints. `a || b || c || d` parses as `((a || b) || c) || d`: taken as a
 * tree, only the last operands were shallow enough to be hinted, and each inner `(…) || c` put a second
 * value on the spot where `c` ends.
 */
class SubExpressionHintsTest {

    private fun hints(expr: String, payload: String): List<SubExpressionHints.Hint> =
        SubExpressionHints.compute(expr, FrontendExpressionEvaluator.evaluateTraced(expr, payload).entries)

    private fun labelled(expr: String, payload: String) = hints(expr, payload).associate { it.exprText to it.label }

    @Test
    fun everyOperandOfALongOrChainGetsItsValueAndOnlyOne() {
        val expr = "status == 'NEW' || status == 'OPEN' || prio > 3 || owner == 'me' || flw.array.isEmpty(tags) || region == 'EU'"
        val payload = """{"status":"CLOSED","prio":1,"owner":"you","tags":[],"region":"EU"}"""
        val h = labelled(expr, payload)
        assertEquals(" = false", h["status == 'NEW'"])
        assertEquals(" = false", h["status == 'OPEN'"])
        assertEquals(" = false", h["prio > 3"])
        assertEquals(" = false", h["owner == 'me'"])
        assertEquals(" = true", h["flw.array.isEmpty(tags)"])
        // the evaluation stopped at the first true operand — the one after it says it was skipped
        assertEquals(" (skipped)", h["region == 'EU'"])
        assertEquals("six operands, six hints, no chain links", 6, hints(expr, payload).size)
        val anchors = hints(expr, payload).map { it.anchor }
        assertEquals("never two values on one spot", anchors.size, anchors.toSet().size)
    }

    @Test
    fun anAndInsideAnOrShowsItsOperands() {
        // the && group ends where `b == 2` ends: one spot, one value — the operand's
        val expr = "a == 1 && b == 2 || c == 3"
        val h = labelled(expr, """{"a":1,"b":2,"c":0}""")
        assertEquals(null, h["a == 1 && b == 2"])
        assertEquals(" = true", h["a == 1"])
        assertEquals(" = true", h["b == 2"])
        assertEquals(" (skipped)", h["c == 3"])
    }

    @Test
    fun plainFlagsInALogicalChainAreHintedToo() {
        val h = labelled("x.enabled || y.enabled", """{"x":{"enabled":false},"y":{"enabled":true}}""")
        assertEquals(" = false", h["x.enabled"])
        assertEquals(" = true", h["y.enabled"])
    }

    @Test
    fun aSumChainHasNoSecondValueWhereItsLastOperandEnds() {
        val expr = "(a * 2) + (b * 2) + (c * 2)"
        val hs = hints(expr, """{"a":1,"b":2,"c":3}""")
        assertEquals(listOf("a * 2" to " = 2", "b * 2" to " = 4", "c * 2" to " = 6"), hs.map { it.exprText to it.label })
    }

    @Test
    fun aParenthesizedGroupKeepsItsValueAfterItsParen() {
        val h = labelled("(a == 1 && b == 2) || c == 3", """{"a":1,"b":0,"c":3}""")
        assertEquals(" = false", h["a == 1 && b == 2"])
        assertEquals(" = false", h["b == 2"])
        assertEquals(" = true", h["c == 3"])
    }

    @Test
    fun aPipesStagesAreEachHinted() {
        val expr = "xs |> flw.array.reverse |> flw.array.first"
        val labels = hints(expr, """{"xs":[3,1,2]}""").map { it.label }
        assertTrue(labels.toString(), labels.any { it.startsWith(" = [2") })
    }

    @Test
    fun afterAnErrorNothingClaimsToBeSkipped() {
        // the evaluation stopped at the unknown function; the operand after it was never reached
        val h = labelled("prio > 3 || flw.nope(tags) || region == 'EU'", """{"prio":1,"tags":[],"region":"EU"}""")
        assertEquals(" = false", h["prio > 3"])
        assertEquals(null, h["region == 'EU'"])
    }
}
