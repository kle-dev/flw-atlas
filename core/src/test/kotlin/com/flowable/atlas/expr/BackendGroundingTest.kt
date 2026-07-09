package com.flowable.atlas.expr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendGroundingTest {

    private fun refs(body: String) = BackendGrounding.rootReferences(body).map { it.name }.toSet()

    @Test fun collectsRootVariableReferences() {
        assertEquals(setOf("order"), refs("order.customer.name"))
        assertEquals(setOf("a", "b"), refs("a > 5 && b < 10"))
        assertEquals(setOf("execution"), refs("execution.getVariable('x')"))
        assertEquals(setOf("items"), refs("items[0]"))
    }

    @Test fun methodReceiverIsARootButFunctionNameIsNot() {
        // `myBean.doThing(order)` → roots are the receiver `myBean` and the argument `order`.
        assertEquals(setOf("myBean", "order"), refs("myBean.doThing(order)"))
        // bare function `listOf(...)` is not a root; its arguments are.
        assertEquals(setOf("a", "b"), refs("listOf(a, b)"))
    }

    @Test fun namespacedFunctionArgsAreRootsButNameIsNot() {
        assertEquals(emptySet<String>(), refs("date:now()"))
        // vars:get('order') — the variable name is a string literal, so there is no identifier root.
        assertEquals(emptySet<String>(), refs("vars:get('order')"))
        assertEquals(setOf("x"), refs("vars:get(x)"))
    }

    @Test fun arrowParametersAreLocalsNotRoots() {
        assertEquals(setOf("order"), refs("x -> x + order"))
    }

    @Test fun literalsAreNotRoots() {
        assertEquals(emptySet<String>(), refs("1 + 2 * 3"))
        assertEquals(emptySet<String>(), refs("'a' == 'b'"))
    }

    @Test fun checkWarnsOnUnknownRootsOnly() {
        val known = setOf("execution", "task", "order")
        val problems = BackendGrounding.check("order.total > threshold", isKnown = { it in known })
        assertEquals(1, problems.size)
        assertEquals(ExprSeverity.WARNING, problems[0].severity)
        assertTrue(problems[0].message.contains("threshold"))
    }

    @Test fun checkOffersSuggestion() {
        val problems = BackendGrounding.check(
            "ordr.total",
            isKnown = { it == "order" },
            suggest = { if (it == "ordr") "order" else null },
        )
        assertEquals(1, problems.size)
        assertEquals("order", problems[0].quickFix)
    }

    @Test fun checkIsCleanWhenAllKnown() {
        assertTrue(BackendGrounding.check("execution.getVariable('x') == 5", isKnown = { it == "execution" }).isEmpty())
    }
}
