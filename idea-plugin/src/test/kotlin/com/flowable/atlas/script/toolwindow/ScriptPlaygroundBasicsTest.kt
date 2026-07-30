package com.flowable.atlas.script.toolwindow

import com.flowable.atlas.expr.ExprSeverity
import com.flowable.atlas.expr.toolwindow.PlaygroundProblemsStrip
import com.flowable.atlas.script.ScriptContext
import com.flowable.atlas.script.ScriptProblemKind
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** State roundtrip, language mapping and the diagnostics compute seam of the Script Playground. */
class ScriptPlaygroundBasicsTest : BasePlatformTestCase() {

    fun testStateRoundtripAndDefaults() {
        val state = FlowableScriptPlaygroundState()
        assertEquals("groovy", state.format)
        assertEquals("", state.script)
        assertEquals(ScriptContext.BPMN_SCRIPT_TASK, state.context)
        state.script = "def x = 1"
        state.format = "javascript"
        state.context = ScriptContext.ACTION_BOT
        val reloaded = FlowableScriptPlaygroundState().apply { loadState(state.state) }
        assertEquals("def x = 1", reloaded.script)
        assertEquals("javascript", reloaded.format)
        assertEquals(ScriptContext.ACTION_BOT, reloaded.context)
        reloaded.format = " "
        assertEquals("blank format falls back to groovy", "groovy", reloaded.format)
    }

    fun testLanguageMapping() {
        // Groovy is on the test classpath via testBundledPlugin; JavaScript is paid-tier-only and
        // does not load in the test IDE — its lookup must fall back to plain text, never throw.
        assertEquals("Groovy", PlaygroundScriptLanguage.GROOVY.ideLanguage().id)
        val js = PlaygroundScriptLanguage.JAVASCRIPT.ideLanguage()
        assertTrue(js.id in setOf("ECMAScript 6", "JavaScript") || js == PlainTextLanguage.INSTANCE)
        // no Python plugin in the test IDE — the fallback must be plain text, never an error
        if (Language.findLanguageByID("Python") == null) {
            assertEquals(PlainTextLanguage.INSTANCE, PlaygroundScriptLanguage.PYTHON.ideLanguage())
        }
        for (lang in PlaygroundScriptLanguage.entries) assertNotNull(lang.fileType())
        assertEquals(PlaygroundScriptLanguage.JAVASCRIPT, PlaygroundScriptLanguage.fromFormat("js"))
        assertEquals(PlaygroundScriptLanguage.JAVASCRIPT, PlaygroundScriptLanguage.fromFormat("nashorn"))
        assertEquals(PlaygroundScriptLanguage.PYTHON, PlaygroundScriptLanguage.fromFormat("jython"))
        assertEquals(PlaygroundScriptLanguage.GROOVY, PlaygroundScriptLanguage.fromFormat(null))
        assertEquals(PlaygroundScriptLanguage.GROOVY, PlaygroundScriptLanguage.fromFormat("kotlin"))
    }

    fun testComputeFindingsCombinesValidatorAndVars() {
        val (problems, vars) = ScriptPlaygroundDiagnostics.computeFindings(
            "def stamp = orderId\nexecution.setVariable('shippingStamp', (stamp", "groovy")
        assertEquals(listOf(ScriptProblemKind.UNCLOSED_OPENER), problems.map { it.kind })
        assertTrue("shippingStamp" in vars.api)
        assertTrue("orderId" in vars.reads)
        // playground mode never nags about empty bodies or a missing format
        assertTrue(ScriptPlaygroundDiagnostics.computeFindings("", "groovy").first.isEmpty())
    }

    fun testComputeFindingsAppliesTheContextBindings() {
        val (typo, _) = ScriptPlaygroundDiagnostics.computeFindings(
            "execution.setTransientVariabel('m', 1)", "groovy", ScriptContext.BPMN_SCRIPT_TASK)
        assertEquals(listOf(ScriptProblemKind.UNKNOWN_MEMBER), typo.map { it.kind })
        val (wrongRoot, _) = ScriptPlaygroundDiagnostics.computeFindings(
            "execution.setVariable('x', 1)", "groovy", ScriptContext.ACTION_BOT)
        assertEquals(listOf(ScriptProblemKind.WRONG_CONTEXT_ROOT), wrongRoot.map { it.kind })
        // default UNKNOWN context: structural checks only, semantics stay silent
        assertTrue(ScriptPlaygroundDiagnostics.computeFindings(
            "execution.setTransientVariabel('m', 1)", "groovy").first.isEmpty())
    }

    fun testStripRowMappingKeepsSeverityAndOffset() {
        val fromScript = ScriptPlaygroundDiagnostics.computeFindings("foo((", "groovy").first
            .map { PlaygroundProblemsStrip.Row(it.severity == ExprSeverity.ERROR, it.message, it.startOffset) }
        assertEquals(1, fromScript.size)
        assertTrue(fromScript[0].isError)
        assertEquals(4, fromScript[0].offset)   // the innermost unclosed '('
    }
}
