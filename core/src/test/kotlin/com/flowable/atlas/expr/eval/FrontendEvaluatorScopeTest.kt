package com.flowable.atlas.expr.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scoped evaluation — `{{…}}` as a component inside a subform/list would see it. The reference
 * semantics come from `@flowable/forms` (`FormUtils.ts` / `Subform.tsx`): the scoped node's keys are
 * the local scope, `$item`/`$index` bind at the innermost list level, `$itemParent` chains one link
 * per container boundary, and `root`/`$payload` never move.
 */
class FrontendEvaluatorScopeTest {

    private val payload = """
        {
          "customerName": "Globex",
          "customer": {"name": "Kevin", "vip": true},
          "orders": [
            {"orderNo": "A-1", "items": [{"qty": 1, "price": 10}]},
            {"orderNo": "A-2", "items": []},
            {"orderNo": "A-3", "items": [{"qty": 3, "price": 5}, {"qty": 4, "price": 2}]}
          ]
        }
    """.trimIndent()

    private fun path(text: String): PayloadScopePath =
        (PayloadScopePath.parse(text) as PayloadScopePath.ParseResult.Ok).path

    private fun value(expr: String, scope: String, payload: String = this.payload): Any? {
        val r = FrontendExpressionEvaluator.evaluate(expr, payload, path(scope))
        assertTrue("expected success for '$expr' @ '$scope' but got: ${(r as? EvalResult.Err)?.message}", r is EvalResult.Ok)
        return (r as EvalResult.Ok).value
    }

    private fun error(expr: String, scope: String, payload: String = this.payload): String {
        val r = FrontendExpressionEvaluator.evaluate(expr, payload, path(scope))
        assertTrue("expected error for '$expr' @ '$scope' but got: $r", r is EvalResult.Err)
        return (r as EvalResult.Err).message
    }

    @Test fun itemSliceFieldsResolveDirectly() {
        assertEquals(3.0, value("qty", "orders[2].items[0]"))
        assertEquals(15.0, value("qty * price", "orders[2].items[0]"))
    }

    @Test fun itemAndIndexBind() {
        assertEquals(3.0, value("\$item.qty", "orders[2].items[0]"))
        assertEquals(0.0, value("\$index", "orders[2].items[0]"))
        assertEquals(2.0, value("\$index + 1", "orders[2].items[1]"))   // Double arithmetic
    }

    @Test fun itemParentChains() {
        assertEquals("A-3", value("\$itemParent.orderNo", "orders[2].items[0]"))
        assertEquals("Globex", value("\$itemParent.\$itemParent.customerName", "orders[2].items[0]"))
        assertNull(value("\$itemParent.\$itemParent.\$itemParent", "orders[2].items[0]"))   // past the top → null
    }

    @Test fun innerListShadowsOuter() {
        // at the order row (one list level): $item is the order, $index its position
        assertEquals("A-3", value("\$item.orderNo", "orders[2]"))
        assertEquals(2.0, value("\$index", "orders[2]"))
        // one level deeper the item row shadows both; the order is only reachable via $itemParent
        assertEquals(4.0, value("\$item.qty", "orders[2].items[1]"))
        assertEquals(1.0, value("\$index", "orders[2].items[1]"))
    }

    @Test fun keyOnlyStepIsASubformWithoutItem() {
        assertEquals("Kevin", value("name", "customer"))
        assertNull(value("\$item", "customer"))                          // no list level → no $item
        assertEquals("Globex", value("\$itemParent.customerName", "customer"))
    }

    @Test fun rootAndPayloadStayAbsolute() {
        assertEquals(3.0, value("\$payload.orders.length", "orders[2].items[0]"))
        // no literal "root" key → root falls back to the whole payload (existing evaluator behavior)
        assertEquals("Globex", value("root.customerName", "orders[2].items[0]"))
        // a literal "root" key wins, at any depth — same as unscoped evaluation
        val withRoot = """{"root": {"appTitle": "Orders"}, "rows": [{"x": 1}]}"""
        assertEquals("Orders", value("root.appTitle", "rows[0]", withRoot))
    }

    @Test fun scalarItemHasNoSpread() {
        val tags = """{"tags": ["a", "b"], "other": 7}"""
        assertEquals("b", value("\$item", "tags[1]", tags))
        assertEquals(1.0, value("\$index", "tags[1]", tags))
        assertNull(value("other", "tags[1]", tags))                      // scalar scope → nothing spread
        assertEquals(7.0, value("\$itemParent.other", "tags[1]", tags))
    }

    @Test fun bareIndexIntoNestedArray() {
        val matrix = """{"matrix": [[1, 2], [3, 4]]}"""
        assertEquals(3.0, value("\$item", "matrix[1][0]", matrix))
        assertEquals(0.0, value("\$index", "matrix[1][0]", matrix))
        assertEquals(2.0, value("\$itemParent.\$itemParent.matrix.length", "matrix[1][0]", matrix))
    }

    @Test fun sliceShadowsAdditionalData() {
        val shadowing = """{"arr": [{"${'$'}item": "shadow", "qty": 1}]}"""
        assertEquals("shadow", value("\$item", "arr[0]", shadowing))     // slice key wins, like the browser spread
    }

    @Test fun unresolvablePathsAreLoudErrors() {
        assertTrue(error("qty", "orders[5]").contains("index 5 is out of bounds (3 items)"))
        assertTrue(error("qty", "missing.x").contains("key 'missing' does not exist"))
        assertTrue(error("qty", "orders[1].items[0]").contains("out of bounds (0 items)"))
        assertTrue(error("qty", "customerName[0]").contains("is not an array"))
        assertTrue(error("qty", "customer.name.deeper").contains("is not an object"))
        // the message names the full path so a stale scope is obvious in the result pane
        assertTrue(error("qty", "orders[5]").startsWith("Scope path 'orders[5]' does not resolve"))
    }

    @Test fun rootScopeBehavesLikeUnscoped() {
        val scoped = FrontendExpressionEvaluator.evaluate("customerName", payload, PayloadScopePath.ROOT)
        assertEquals("Globex", (scoped as EvalResult.Ok).value)
        val unscoped = FrontendExpressionEvaluator.evaluate("\$item", payload)
        assertNull((unscoped as EvalResult.Ok).value)                    // unscoped: $item stays unbound
    }

    @Test fun tracedEvaluationCarriesTheScope() {
        val t = FrontendExpressionEvaluator.evaluateTraced("qty * 2", payload, path("orders[2].items[0]"))
        assertEquals(6.0, (t.result as EvalResult.Ok).value)
        assertEquals(6.0, (t.entries.single { it.depth == 0 }.outcome as TraceOutcome.Value).value)
    }
}
