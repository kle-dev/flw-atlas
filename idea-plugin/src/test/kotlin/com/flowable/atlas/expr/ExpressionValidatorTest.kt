package com.flowable.atlas.expr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionValidatorTest {

    private fun backend(body: String) = ExpressionValidator.validate(body, ExpressionDialect.BACKEND)
    private fun frontend(body: String) = ExpressionValidator.validate(body, ExpressionDialect.FRONTEND)

    @Test
    fun validBackendExpressionsHaveNoProblems() {
        assertTrue(backend("date:now()").isEmpty())
        assertTrue(backend("vars:get('order')").isEmpty())
        assertTrue(backend("execution.getVariable('a')").isEmpty())
        assertTrue(backend("a == b ? c : d").isEmpty()) // ternary colon is not a namespace call
    }

    @Test
    fun validFrontendExpressionsHaveNoProblems() {
        assertTrue(frontend("flw.sum(items)").isEmpty())
        assertTrue(frontend("total |> flw.round(2)").isEmpty())
        assertTrue(frontend("flw.remove.nulls(list)").isEmpty())
    }

    @Test
    fun workInjectedFlwMembersAreValid() {
        // Work/platform-injected flw.* members (useGlobalResolver + Form.tsx), not base @flowable/forms.
        assertTrue(frontend("flw.getUser('userId').displayName").isEmpty())
        assertTrue(frontend("flw.getMasterDataInstance('id').name").isEmpty())
        assertTrue(frontend("flw.getMasterDataInstanceByKey('k', 'd').name").isEmpty())
        assertTrue(frontend("flw.getDataObjectInstance('d', 'o', 'k', 'v').variableOne").isEmpty())
        assertTrue(frontend("flw.validate('componentId')").isEmpty())
        assertTrue(frontend("flw.stringify(payload)").isEmpty())
    }

    @Test
    fun emptyInterpolationIsClean() {
        // An empty `{{}}` / `${}` is a runtime no-op, not a syntax error.
        assertTrue(frontend("{{}}").isEmpty())
        assertTrue(backend("\${}").isEmpty())
        assertTrue(frontend("{{  }}").isEmpty())
    }

    @Test
    fun unbalancedParenIsError() {
        val p = backend("date:now(")
        assertTrue(p.any { it.severity == ExprSeverity.ERROR && it.message.contains("Unclosed") })
    }

    @Test
    fun unterminatedStringIsError() {
        val p = backend("vars:get('order)")
        assertTrue(p.any { it.severity == ExprSeverity.ERROR && it.message.contains("Unterminated") })
    }

    @Test
    fun unknownNamespaceWarnsWithSuggestion() {
        val p = backend("daate:now()")
        val w = p.single { it.severity == ExprSeverity.WARNING }
        assertTrue(w.message.contains("Unknown function namespace"))
        assertEquals("date", w.quickFix)
    }

    @Test
    fun unknownBackendFunctionWarnsWithSuggestion() {
        val p = backend("date:noww()")
        val w = p.single { it.severity == ExprSeverity.WARNING }
        assertTrue(w.message.contains("Unknown function"))
        assertEquals("now", w.quickFix)
    }

    @Test
    fun unknownFrontendMemberWarnsWithSuggestion() {
        val p = frontend("flw.sim(items)")
        val w = p.single { it.severity == ExprSeverity.WARNING }
        assertTrue(w.message.contains("flw.sim"))
        assertEquals("sum", w.quickFix)
    }

    @Test
    fun pipeInBackendIsWarned() {
        assertTrue(backend("a |> b").any { it.message.contains("frontend-only pipe") })
    }

    @Test
    fun backendFunctionSyntaxInFrontendIsWarned() {
        assertTrue(frontend("date:now()").any { it.message.contains("backend function syntax") })
    }

    @Test
    fun emptyArgumentIsError() {
        assertTrue(backend("date:now(,)").any { it.message.contains("Empty argument") })
    }

    @Test
    fun trailingOperatorIsError() {
        assertTrue(backend("a +").any { it.message.contains("ends unexpectedly") })
    }

    @Test
    fun knownFunctionWithPipeIsCleanInFrontend() {
        assertFalse(frontend("a |> flw.sum(b)").any { it.severity == ExprSeverity.ERROR })
    }

    @Test
    fun bodyWrappedInDelimitersIsToleratedInPlayground() {
        // Users naturally type the wrapper; the '{' / '}' must not be flagged as stray characters.
        assertTrue(frontend("{{test && test}}").isEmpty())
        assertTrue(frontend("{{ flw.sum(items) }}").isEmpty())
        assertTrue(backend("\${ date:now() }").isEmpty())
        assertTrue(backend("#{execution.id}").isEmpty())
    }

    @Test
    fun problemOffsetsPointIntoOriginalTextWhenWrapped() {
        val text = "\${date:noww()}"
        val w = backend(text).single { it.severity == ExprSeverity.WARNING }
        assertEquals("noww", text.substring(w.startOffset, w.endOffset))
    }

    @Test
    fun operatorBeforeClosingParenIsFlagged() {
        // The reported case: '||' has no right operand before ')'.
        val p = frontend("{{test && (test || )}}")
        assertTrue(p.any { it.severity == ExprSeverity.ERROR && it.message.contains("missing its right operand") })
    }

    @Test
    fun binaryOperatorWithoutLeftOperandIsFlagged() {
        assertTrue(backend("(&& b)").any { it.message.contains("missing its left operand") })
    }

    @Test
    fun dotWithoutNameIsFlagged() {
        assertTrue(backend("execution.").any { it.message.contains("Expected a name after") })
    }

    @Test
    fun unaryOperatorsDoNotFalselyFlag() {
        assertTrue(backend("(-1)").isEmpty())
        assertTrue(backend("a + -b").isEmpty())
        assertTrue(frontend("!done").isEmpty())
        assertTrue(frontend("a && !done").isEmpty())
    }

    @Test
    fun wellFormedNestedExpressionIsValid() {
        assertTrue(frontend("test && (test2 || other)").isEmpty())
        assertTrue(backend("(a == b) && (c != d)").isEmpty())
    }

    @Test
    fun juxtaposedOperandsAreAnError() {
        // JUEL rejects two operands with no operator between them (`a b c`); the token heuristics missed this.
        assertTrue(backend("a b c").any { it.severity == ExprSeverity.ERROR })
    }

    @Test
    fun backendArrayLiteralIsAnError() {
        // JUEL has no array literals — you use listOf(...). `items[0]` index access stays valid.
        assertTrue(backend("[1, 2, 3]").any { it.severity == ExprSeverity.ERROR })
        assertTrue(backend("items[0]").isEmpty())
    }

    @Test
    fun frontendArrayLiteralAndHigherOrderAreValid() {
        assertTrue(frontend("[1, 2, 3]").isEmpty())
        assertFalse(frontend("flw.array.filter(items, item => item > 2)").any { it.severity == ExprSeverity.ERROR })
    }

    @Test
    fun backendWordOperatorsAreValid() {
        assertTrue(backend("empty items").isEmpty())
        assertTrue(backend("a lt b and not done").isEmpty())
        assertTrue(backend("a div b mod 2").isEmpty())
    }
}
