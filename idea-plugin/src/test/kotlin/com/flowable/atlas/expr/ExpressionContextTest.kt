package com.flowable.atlas.expr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionContextTest {

    private fun be(text: String, caret: Int) = ExpressionContext.classify(text, caret, ExpressionDialect.BACKEND)
    private fun fe(text: String, caret: Int) = ExpressionContext.classify(text, caret, ExpressionDialect.FRONTEND)

    @Test
    fun emptyIsRoot() {
        assertTrue(be("", 0) is ExprCompletionContext.Root)
    }

    @Test
    fun bareIdentifierIsRootWithPrefix() {
        val c = be("exec", 4)
        assertTrue(c is ExprCompletionContext.Root)
        assertEquals("exec", c.prefix)
    }

    @Test
    fun afterKnownNamespace() {
        val c = be("vars:", 5)
        assertTrue(c is ExprCompletionContext.AfterNamespace)
        assertEquals("vars", (c as ExprCompletionContext.AfterNamespace).namespace)
        assertEquals("", c.prefix)
    }

    @Test
    fun afterNamespaceWithTypedPrefix() {
        val c = be("date:no", 7)
        assertTrue(c is ExprCompletionContext.AfterNamespace)
        assertEquals("date", (c as ExprCompletionContext.AfterNamespace).namespace)
        assertEquals("no", c.prefix)
    }

    @Test
    fun unknownNamespaceFallsBackToRoot() {
        // 'foo' is not a Flowable prefix → the ':' is treated as a ternary else, not a namespace.
        assertTrue(be("foo:", 4) is ExprCompletionContext.Root)
    }

    @Test
    fun afterDotFrontend() {
        val c = fe("flw.", 4)
        assertTrue(c is ExprCompletionContext.AfterDot)
        assertEquals("flw", (c as ExprCompletionContext.AfterDot).receiver)
    }

    @Test
    fun afterNestedDotFrontend() {
        val c = fe("flw.remove.", 11)
        assertTrue(c is ExprCompletionContext.AfterDot)
        assertEquals("remove", (c as ExprCompletionContext.AfterDot).receiver)
    }

    @Test
    fun ternaryColonIsRootNotNamespace() {
        assertTrue(be("a ? b : ", 8) is ExprCompletionContext.Root)
    }
}
