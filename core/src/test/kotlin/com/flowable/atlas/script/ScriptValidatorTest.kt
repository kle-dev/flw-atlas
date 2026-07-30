package com.flowable.atlas.script

import com.flowable.atlas.expr.ExprSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptValidatorTest {

    private fun clean(script: String, format: String) {
        val problems = ScriptValidator.validate(script, format)
        assertEquals("expected no findings for valid $format script, got: $problems", emptyList<ScriptProblem>(), problems)
    }

    private fun only(script: String?, format: String?, formatRequired: Boolean = false): ScriptProblem {
        val problems = ScriptValidator.validate(script, format, formatRequired)
        assertEquals("expected exactly one finding, got: $problems", 1, problems.size)
        return problems[0]
    }

    // ---- the product: realistic, valid scripts must produce ZERO findings -----------------------

    @Test
    fun validGroovyIsClean() {
        clean(
            """
            // stamp the order
            def parts = orderId.split('-')
            def label = "ord ${'$'}{parts[0]} (${'$'}{status ? "open${'$'}{level}" : 'closed'})"
            execution.setVariable('shippingStamp', label)
            def doc = '''
                multi (line) [text] {with brackets}
            '''
            def ratio = total / count / 2
            return (ratio > 0.5) ? label : doc
            """.trimIndent(), "groovy")
    }

    @Test
    fun groovySlashyRegexesAreClean() {
        clean(
            """
            def m = text =~ /\d+(\.\d+)?/
            if (text ==~ /a[)(]b/) { execution.setVariable('ok', true) }
            def cols = line.split(/,/)
            def esc = /a\/b/
            def money = $/costs ${'$'}${'$'}5 (net)/$
            """.trimIndent(), "groovy")
    }

    @Test
    fun groovyDivisionChainsAreNeverRegexes() {
        clean(
            """
            def a = total / count
            def b = (x + y) / 2
            def c = a / b / 3
            def half = { it -> it / 2 }
            i++ / 2
            """.trimIndent(), "groovy")
    }

    @Test
    fun groovyRegexContextWithoutCloserFailsTowardSilence() {
        // `= /` starts a multiline slashy in real Groovy; we deliberately read it as division
        // (silence) rather than risk masking legitimate code between two distant slashes.
        clean("def x = /never closed on this line\ndef y = 2", "groovy")
    }

    @Test
    fun groovyStringsInsideInterpolationAreClean() {
        clean("""def s = "${'$'}{'}'}" + "${'$'}{map['k']}"""", "groovy")
    }

    @Test
    fun validJavaScriptIsClean() {
        clean(
            """
            const parts = orderId.split('-');
            const label = `ord ${'$'}{parts[0]} (${'$'}{status ? `open` : 'closed'})`;
            const re = /[)(]+/g;
            const clean = text.replace(/a\/b/, '');
            const half = (a) => a / 2;
            execution.setVariable('shippingStamp', label);
            """.trimIndent(), "javascript")
    }

    @Test
    fun validPythonIsClean() {
        clean(
            """
            # brackets ({[ in a comment
            names = [n.strip() for n in raw.split(',')]
            text = "it's fine"
            doc = '''
              (unbalanced { in a docstring
            '''
            execution.setVariable('names', names)
            """.trimIndent(), "python")
    }

    @Test
    fun juelAndUnknownLanguagesAreNotScanned() {
        clean("${'$'}{broken(", "juel")
        clean("val x = listOf(", "kotlin")   // distant name: a custom JSR-223 engine, not a typo
    }

    // ---- structural findings with exact offsets --------------------------------------------------

    @Test
    fun unclosedOpenerIsReportedAtTheOpener() {
        val p = only("if (x) {\n  foo()\n", "groovy")
        assertEquals(ScriptProblemKind.UNCLOSED_OPENER, p.kind)
        assertEquals(ExprSeverity.ERROR, p.severity)
        assertEquals(7, p.startOffset)
        assertEquals("'{' is never closed", p.message)
    }

    @Test
    fun unmatchedCloserIsReportedAtTheCloser() {
        val p = only("foo())", "groovy")
        assertEquals(ScriptProblemKind.UNMATCHED_CLOSER, p.kind)
        assertEquals(5, p.startOffset)
    }

    @Test
    fun mismatchedCloserNamesTheOpenLine() {
        val p = only("foo(]", "groovy")
        assertEquals(ScriptProblemKind.MISMATCHED_CLOSER, p.kind)
        assertEquals(4, p.startOffset)
        assertTrue(p.message, p.message.contains("'('") && p.message.contains("line 1"))
    }

    @Test
    fun unterminatedSingleLineStringIsReportedAtTheQuote() {
        val p = only("def s = 'abc\nfoo()", "groovy")
        assertEquals(ScriptProblemKind.UNTERMINATED_STRING, p.kind)
        assertEquals(8, p.startOffset)
    }

    @Test
    fun unterminatedTripleQuotedStringIsReported() {
        val p = only("\"\"\"abc", "groovy")
        assertEquals(ScriptProblemKind.UNTERMINATED_STRING, p.kind)
        assertEquals(0, p.startOffset)
        assertTrue(p.message, p.message.contains("triple-quoted"))
    }

    @Test
    fun unterminatedBlockCommentIsReported() {
        val p = only("foo()\n/* never closed", "groovy")
        assertEquals(ScriptProblemKind.UNTERMINATED_COMMENT, p.kind)
        assertEquals(6, p.startOffset)
    }

    @Test
    fun unclosedGStringInterpolationIsReported() {
        val p = only("def s = \"a\${x", "groovy")
        assertEquals(ScriptProblemKind.UNCLOSED_INTERPOLATION, p.kind)
        assertEquals(10, p.startOffset)
    }

    @Test
    fun unclosedTemplateInterpolationIsReported() {
        val p = only("const s = `a\${x", "javascript")
        assertEquals(ScriptProblemKind.UNCLOSED_INTERPOLATION, p.kind)
        assertEquals(12, p.startOffset)
    }

    @Test
    fun pythonBracketBalanceIsChecked() {
        val p = only("print((1, 2)", "python")
        assertEquals(ScriptProblemKind.UNCLOSED_OPENER, p.kind)
        assertEquals(5, p.startOffset)
    }

    // ---- format findings -------------------------------------------------------------------------

    @Test
    fun formatTypoGetsADidYouMean() {
        val p = only("execution.setVariable('x', 1)", "grooy")
        assertEquals(ScriptProblemKind.UNKNOWN_FORMAT, p.kind)
        assertEquals(ExprSeverity.WARNING, p.severity)
        assertEquals("groovy", p.quickFix)
    }

    @Test
    fun missingFormatWarnsOnlyWhereRequired() {
        val p = only("execution.setVariable('x', 1)", null, formatRequired = true)
        assertEquals(ScriptProblemKind.MISSING_FORMAT, p.kind)
        assertEquals(ExprSeverity.WARNING, p.severity)
        assertTrue(ScriptValidator.validate("execution.setVariable('x', 1)", null).isEmpty())
    }

    @Test
    fun emptyBodyWarnsOnlyWhereRequired() {
        val p = only("  ", "groovy", formatRequired = true)
        assertEquals(ScriptProblemKind.EMPTY_BODY, p.kind)
        assertTrue(ScriptValidator.validate(null, "groovy").isEmpty())
    }

    // ---- semantic layer (bindings catalog) --------------------------------------------------------

    private fun onlySemantic(script: String, format: String, context: ScriptContext): ScriptProblem {
        val problems = ScriptValidator.validate(script, format, context = context)
        assertEquals("expected exactly one finding, got: $problems", 1, problems.size)
        return problems[0]
    }

    @Test
    fun validApiUsagePerContextIsClean() {
        val bpmn = """
            def metadata = [source: 'atlas']
            execution.setTransientVariable("metadata", metadata)
            execution.setVariableLocal('x', execution.getProcessInstanceId())
            flw.setOutput('total', flw.math.sum([1, 2]))
            runtimeService.createProcessInstanceQuery()
        """.trimIndent()
        assertEquals(emptyList<ScriptProblem>(),
            ScriptValidator.validate(bpmn, "groovy", context = ScriptContext.BPMN_SCRIPT_TASK))
        val cmmn = "planItemInstance.setVariable('c', caseInstance.getCaseDefinitionKey())"
        assertEquals(emptyList<ScriptProblem>(),
            ScriptValidator.validate(cmmn, "groovy", context = ScriptContext.CMMN_SCRIPT_TASK))
        val bot = "flw.setOutput('when', flw.time.now())\nflwActionContext.setIntent('reload')"
        assertEquals(emptyList<ScriptProblem>(),
            ScriptValidator.validate(bot, "javascript", context = ScriptContext.ACTION_BOT))
    }

    @Test
    fun memberTypoGetsADidYouMean() {
        val p = onlySemantic("execution.setTransientVariabel('metadata', 1)", "groovy",
            ScriptContext.BPMN_SCRIPT_TASK)
        assertEquals(ScriptProblemKind.UNKNOWN_MEMBER, p.kind)
        assertEquals(ExprSeverity.WARNING, p.severity)
        assertEquals("setTransientVariable", p.quickFix)
        assertEquals("execution.".length, p.startOffset)
    }

    @Test
    fun flwSubObjectTyposAreCaught() {
        val p = onlySemantic("flw.setOutput('s', flw.strng.capitalize(name))", "groovy",
            ScriptContext.BPMN_SCRIPT_TASK)
        assertEquals(ScriptProblemKind.UNKNOWN_MEMBER, p.kind)
        assertEquals("string", p.quickFix)
        val q = onlySemantic("flw.time.nows()", "groovy", ScriptContext.ACTION_BOT)
        assertEquals("now", q.quickFix)
    }

    @Test
    fun distantUnknownMembersStaySilent() {
        // dynamic Groovy / project extensions must never be flagged
        clean("execution.doSomethingCustom(1)", "groovy")
        assertEquals(emptyList<ScriptProblem>(), ScriptValidator.validate(
            "execution.setVariableAndLog('x', 1)", "groovy", context = ScriptContext.BPMN_SCRIPT_TASK))
        // Groovy property syntax is deliberately unchecked
        assertEquals(emptyList<ScriptProblem>(), ScriptValidator.validate(
            "def id = execution.id", "groovy", context = ScriptContext.BPMN_SCRIPT_TASK))
    }

    @Test
    fun unknownContextRunsNoSemanticChecks() {
        assertEquals(emptyList<ScriptProblem>(), ScriptValidator.validate(
            "execution.setTransientVariabel('m', 1)", "groovy", context = ScriptContext.UNKNOWN))
    }

    @Test
    fun wrongContextRootIsExplained() {
        val p = onlySemantic("execution.setVariable('x', 1)", "groovy", ScriptContext.CMMN_SCRIPT_TASK)
        assertEquals(ScriptProblemKind.WRONG_CONTEXT_ROOT, p.kind)
        assertTrue(p.message, p.message.contains("planItemInstance"))
        val q = onlySemantic("execution.setVariable('x', 1)", "groovy", ScriptContext.BPMN_TASK_LISTENER)
        assertTrue(q.message, q.message.contains("task"))
        val r = onlySemantic("task.setAssignee('kermit')", "groovy", ScriptContext.BPMN_SCRIPT_TASK)
        assertEquals(ScriptProblemKind.WRONG_CONTEXT_ROOT, r.kind)
    }

    @Test
    fun locallyBoundNamesShadowTheCatalog() {
        clean("def task = taskService.createTaskQuery().singleResult()\ntask.getName()", "groovy")
        assertEquals(emptyList<ScriptProblem>(), ScriptValidator.validate(
            "def task = [name: 'x']\ntask.getNam()", "groovy", context = ScriptContext.BPMN_SCRIPT_TASK))
    }

    @Test
    fun elOnlyFlwNamespacesAreFlagged() {
        val p = onlySemantic("flw.setOutput('x', flw.base64.encode(data))", "groovy",
            ScriptContext.ACTION_BOT)
        assertEquals(ScriptProblemKind.EL_ONLY_API, p.kind)
        assertTrue(p.message, p.message.contains("base64"))
    }

    @Test
    fun formatCaseSensitivityIsFlagged() {
        val p = only("execution.getId()", "GROOVY")
        assertEquals(ScriptProblemKind.FORMAT_CASE, p.kind)
        assertEquals("groovy", p.quickFix)
        val q = only("var a = 1;", "Javascript")
        assertEquals("javascript", q.quickFix)
        // registered case variants and plain lowercase names stay silent
        clean("execution.getId()", "Groovy")
        clean("var a = 1;", "JavaScript")
        clean("var a = 1;", "graal.js")
    }

    // ---- dict shape ------------------------------------------------------------------------------

    @Test
    fun problemDictsCarryLineAndSnippet() {
        val dicts = ScriptValidator.problemDicts("def ok = 1\nif (x) {\n  foo()\n", "groovy")
        assertEquals(1, dicts.size)
        val d = dicts[0]
        assertEquals("error", d["severity"])
        assertEquals(2, d["line"])
        assertEquals("unclosed-opener", d["kind"])
        assertEquals("if (x) {", d["snippet"])
        assertTrue((d["start"] as Int) > 0 && d["quickFix"] == null)
    }
}
