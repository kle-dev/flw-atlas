package com.flowable.atlas.script.completion

import com.flowable.atlas.script.ScriptContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The bindings catalog feeds completion: after `execution.` the [ScriptContext]'s API surface is
 * offered — in the playground (context via [ScriptScope.CONTEXT_KEY]) and in injected script bodies
 * (context derived from the model XML). Groovy is on the test classpath via `testBundledPlugin`.
 */
class ScriptBindingsCompletionTest : BasePlatformTestCase() {

    private fun completeWithContext(text: String, context: ScriptContext, fileName: String = "s.groovy"): List<String> {
        myFixture.configureByText(fileName, text)
        myFixture.file.putUserData(ScriptScope.CONTEXT_KEY, context)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings ?: emptyList()
    }

    fun testMemberCompletionAfterExecutionDot() {
        val lookups = completeWithContext(
            "execution.setTr<caret>", ScriptContext.BPMN_SCRIPT_TASK)
        assertTrue("expected setTransientVariable, got: $lookups", "setTransientVariable" in lookups)
        assertTrue("setTransientVariableLocal" in lookups)
        // the popup shows the parameter signature
        val el = myFixture.lookupElements!!.first { it.lookupString == "setTransientVariable" }
        val presentation = com.intellij.codeInsight.lookup.LookupElementPresentation()
        el.renderElement(presentation)
        assertEquals("(variableName, value)", presentation.tailText)
    }

    fun testRootCompletionOffersTheContextBindings() {
        // a single match is auto-inserted (no popup) — the DotInsertHandler appends the dot
        myFixture.configureByText("s.groovy", "exec<caret>")
        myFixture.file.putUserData(ScriptScope.CONTEXT_KEY, ScriptContext.BPMN_SCRIPT_TASK)
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings
        if (lookups == null) {
            assertTrue("expected the auto-inserted root", myFixture.editor.document.text.startsWith("execution."))
        } else {
            assertTrue("expected execution, got: $lookups", "execution" in lookups)
        }
        // with no prefix the whole context shows: scope root + services + flw
        val all = completeWithContext("<caret>", ScriptContext.BPMN_SCRIPT_TASK)
        assertTrue("expected the context roots, got: $all", "runtimeService" in all && "flw" in all)
    }

    fun testContextFiltersTheRoots() {
        val bot = completeWithContext("<caret>", ScriptContext.ACTION_BOT)
        assertTrue("flwActionContext" in bot)
        assertFalse("execution must not be offered in a bot script", "execution" in bot)
        val taskListener = completeWithContext("<caret>", ScriptContext.BPMN_TASK_LISTENER)
        assertTrue("task" in taskListener)
        assertFalse("execution" in taskListener)
    }

    fun testFlwSubObjectChainCompletes() {
        val subs = completeWithContext("flw.<caret>", ScriptContext.ACTION_BOT)
        assertTrue("expected time sub-object, got: $subs", "time" in subs)
        assertTrue("setOutput" in subs)
        val members = completeWithContext("flw.time.plusD<caret>", ScriptContext.ACTION_BOT)
        assertTrue("expected plusDays, got: $members", "plusDays" in members)
    }

    fun testServiceMembersCompleteWithSignatures() {
        val lookups = completeWithContext(
            "runtimeService.startProc<caret>", ScriptContext.BPMN_SCRIPT_TASK)
        assertTrue("expected startProcessInstanceByKey, got: $lookups",
            "startProcessInstanceByKey" in lookups)
        val el = myFixture.lookupElements!!.first { it.lookupString == "startProcessInstanceByKey" }
        val presentation = com.intellij.codeInsight.lookup.LookupElementPresentation()
        el.renderElement(presentation)
        assertEquals("(processDefinitionKey, businessKey, variables)", presentation.tailText)
    }

    fun testUncataloguedRootsOfferNothing() {
        // the engine configuration stays members = null — no invented suggestions
        val lookups = completeWithContext(
            "processEngineConfiguration.<caret>", ScriptContext.BPMN_SCRIPT_TASK)
        assertFalse(lookups.any { it.startsWith("setVariable") })
    }

    fun testPlainTextFallbackStillCompletes() {
        val lookups = completeWithContext(
            "execution.<caret>", ScriptContext.BPMN_SCRIPT_TASK, fileName = "s.txt")
        assertTrue("expected setVariable in plain text host, got size=${lookups.size}",
            "setVariable" in lookups)
    }

    fun testNoContextMeansNoOffer() {
        myFixture.configureByText("s.groovy", "execution.setTr<caret>")
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings ?: emptyList()
        assertFalse("without a context stamp the contributor must stay silent",
            "setTransientVariable" in lookups)
    }

    fun testCompletionInsideInjectedBpmnScriptBody() {
        myFixture.configureByText(
            "task.bpmn20.xml",
            """<?xml version="1.0"?>
               <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                            xmlns:flowable="http://flowable.org/bpmn">
                 <process id="P1">
                   <scriptTask id="s" scriptFormat="groovy">
                     <script>execution.setTr<caret></script>
                   </scriptTask>
                 </process>
               </definitions>""",
        )
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected setTransientVariable inside the injected body, got: $lookups",
            "setTransientVariable" in lookups)
    }
}
