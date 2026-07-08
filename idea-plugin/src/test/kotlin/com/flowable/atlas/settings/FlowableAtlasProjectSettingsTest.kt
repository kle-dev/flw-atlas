package com.flowable.atlas.settings

import com.flowable.atlas.expr.ExprProblem
import com.flowable.atlas.expr.ExprProblemKind
import com.flowable.atlas.expr.ExprSeverity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowableAtlasProjectSettingsTest {

    private fun problem(kind: ExprProblemKind, subject: String?) =
        ExprProblem(0, 1, "m", ExprSeverity.WARNING, null, kind, subject)

    private fun settings(vararg setup: (FlowableAtlasProjectSettings.State) -> Unit): FlowableAtlasProjectSettings {
        val s = FlowableAtlasProjectSettings()
        val state = FlowableAtlasProjectSettings.State()
        setup.forEach { it(state) }
        s.loadState(state)
        return s
    }

    @Test
    fun `namespace entry covers the namespace and its functions`() {
        val s = settings({ it.allowedNamespaces.add("myns") })
        assertTrue(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_NAMESPACE, "myns")))
        assertTrue(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_FUNCTION, "myns:doIt")))
        assertFalse(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_FUNCTION, "other:doIt")))
    }

    @Test
    fun `function entry is exact`() {
        val s = settings({ it.allowedFunctions.add("myns:doIt") })
        assertTrue(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_FUNCTION, "myns:doIt")))
        assertFalse(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_FUNCTION, "myns:other")))
        assertFalse(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_NAMESPACE, "myns")))
    }

    @Test
    fun `flw member allowlisting`() {
        val s = settings({ it.allowedFunctions.add("flw.custom") })
        assertTrue(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_FUNCTION, "flw.custom")))
        assertFalse(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_FUNCTION, "flw.other")))
    }

    @Test
    fun `grounding roots are a separate list`() {
        val s = settings({ it.allowedGroundingRoots.add("runtimeVar") })
        assertTrue(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_ROOT, "runtimeVar")))
        assertFalse(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_FUNCTION, "runtimeVar")))
    }

    @Test
    fun `syntax and dialect problems are never allowlisted`() {
        val s = settings({ it.allowedNamespaces.add("x") }, { it.allowedFunctions.add("x") })
        assertFalse(s.isAllowlisted(problem(ExprProblemKind.SYNTAX, "x")))
        assertFalse(s.isAllowlisted(problem(ExprProblemKind.DIALECT_MISUSE, "x")))
        assertFalse(s.isAllowlisted(problem(ExprProblemKind.UNKNOWN_FUNCTION, null)))
    }

    @Test
    fun `allow() deduplicates and routes by kind`() {
        val s = settings()
        s.allow("myns", ExprProblemKind.UNKNOWN_NAMESPACE)
        s.allow("myns", ExprProblemKind.UNKNOWN_NAMESPACE)
        s.allow("flw.custom", ExprProblemKind.UNKNOWN_FUNCTION)
        s.allow("root1", ExprProblemKind.UNKNOWN_ROOT)
        s.allow("ignored", ExprProblemKind.SYNTAX)
        assertTrue(s.state.allowedNamespaces == mutableListOf("myns"))
        assertTrue(s.state.allowedFunctions == mutableListOf("flw.custom"))
        assertTrue(s.state.allowedGroundingRoots == mutableListOf("root1"))
    }
}
