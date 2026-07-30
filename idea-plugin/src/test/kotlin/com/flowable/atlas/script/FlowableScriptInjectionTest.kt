package com.flowable.atlas.script

import com.flowable.atlas.expr.lang.FlowableBackendExprLanguage
import com.flowable.atlas.script.inject.ScriptInjectionSupport
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlText
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The script-body injectors put the IDE's real Groovy/JavaScript into Flowable script bodies, and
 * the backend-expression injector stands down there (no overlapping `${…}` fragments). The test IDE
 * loads Groovy and JavaScript via `testBundledPlugin` — the shipped plugin only looks languages up
 * by ID at runtime.
 */
class FlowableScriptInjectionTest : BasePlatformTestCase() {

    fun testGroovyInjectedIntoBpmnScriptTask() {
        val host = bpmnScriptHost("""<scriptTask id="s" scriptFormat="groovy">
            <script>def x = 1</script></scriptTask>""", "def x = 1")
        assertTrue("Groovy injected", injectedLanguages(host).contains("Groovy"))
    }

    fun testGroovyInjectedIntoListenerScript() {
        val host = bpmnScriptHost(
            """<serviceTask id="n" flowable:delegateExpression="${'$'}{bean}"><extensionElements>
                 <flowable:executionListener event="end">
                   <flowable:script scriptFormat="groovy">execution.setVariable('done', true)</flowable:script>
                 </flowable:executionListener>
               </extensionElements></serviceTask>""", "setVariable")
        assertTrue("Groovy injected into listener script", injectedLanguages(host).contains("Groovy"))
    }

    fun testGroovyInjectedIntoCmmnScriptFieldWithCdata() {
        myFixture.configureByText(
            "case.cmmn.xml",
            """<?xml version="1.0"?>
               <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
                            xmlns:flowable="http://flowable.org/cmmn">
                 <case id="C1"><casePlanModel id="plan">
                   <task id="scr" flowable:type="script" flowable:scriptFormat="groovy">
                     <extensionElements>
                       <flowable:field name="script">
                         <flowable:string><![CDATA[def a = [1, 2]]]></flowable:string>
                       </flowable:field>
                     </extensionElements>
                   </task>
                 </casePlanModel></case>
               </definitions>""",
        )
        val host = PsiTreeUtil.findChildrenOfType(myFixture.file, XmlText::class.java)
            .firstOrNull { it.text.contains("def a") }
        assertNotNull("CDATA script host must be found", host)
        assertTrue("Groovy injected into CMMN script field", injectedLanguages(host!!).contains("Groovy"))
    }

    fun testUnknownOrMissingFormatIsNotInjected() {
        val none = bpmnScriptHost("""<scriptTask id="s"><script>def x = 1</script></scriptTask>""", "def x")
        assertFalse("no format → no injection", injectedLanguages(none).contains("Groovy"))
        val python = bpmnScriptHost("""<scriptTask id="p" scriptFormat="python">
            <script>x = 1</script></scriptTask>""", "x = 1")
        assertFalse("python → no injection", injectedLanguages(python).contains("Groovy"))
    }

    fun testBackendExprNotDoubleInjectedInsideGroovyBody() {
        val host = bpmnScriptHost("""<scriptTask id="s" scriptFormat="groovy">
            <script>def s = "x-${'$'}{orderId}"</script></scriptTask>""", "orderId")
        val langs = injectedLanguages(host)
        assertTrue("Groovy injected", langs.contains("Groovy"))
        assertFalse("no overlapping backend-expr fragment inside the script body",
            langs.contains(FlowableBackendExprLanguage.id))
    }

    fun testJuelScriptKeepsBackendExprInjection() {
        val host = bpmnScriptHost("""<scriptTask id="s" scriptFormat="juel">
            <script>${'$'}{orderId}</script></scriptTask>""", "orderId")
        assertTrue("juel body keeps the expression injection",
            injectedLanguages(host).contains(FlowableBackendExprLanguage.id))
    }

    fun testJavaScriptInjectedIntoActionScriptInfo() {
        val jsLanguage = ScriptInjectionSupport.scriptLanguage("javascript")
        if (jsLanguage == null) return   // JS plugin not on the test classpath — covered manually
        myFixture.configureByText(
            "notify.action",
            """{"key":"notify","botKey":"script-bot",
                "config":{"scriptInfo":{"language":"javascript","script":"const a = 1;"}}}""",
        )
        val host = PsiTreeUtil.findChildrenOfType(myFixture.file, JsonStringLiteral::class.java)
            .firstOrNull { it.text.contains("const a") }
        assertNotNull("action script host must be found", host)
        assertTrue("JS injected into action script",
            injectedLanguages(host!!).contains(jsLanguage.id))
    }

    fun testFormatAliasesResolveCaseInsensitively() {
        assertEquals(Language.findLanguageByID("Groovy"), ScriptInjectionSupport.scriptLanguage(" Groovy "))
        val js = ScriptInjectionSupport.scriptLanguage("javascript")
        assertEquals(js, ScriptInjectionSupport.scriptLanguage("JS"))
        assertEquals(js, ScriptInjectionSupport.scriptLanguage("graal.js"))
        assertNull(ScriptInjectionSupport.scriptLanguage("juel"))
        assertNull(ScriptInjectionSupport.scriptLanguage("kotlin"))
        assertNull(ScriptInjectionSupport.scriptLanguage(null))
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun bpmnScriptHost(processBody: String, marker: String): XmlText {
        myFixture.configureByText(
            "task.bpmn20.xml",
            """<?xml version="1.0"?>
               <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                            xmlns:flowable="http://flowable.org/bpmn">
                 <process id="P1">$processBody</process>
               </definitions>""",
        )
        val host = PsiTreeUtil.findChildrenOfType(myFixture.file, XmlText::class.java)
            .firstOrNull { it.text.contains(marker) }
        assertNotNull("script host must be found", host)
        return host!!
    }

    private fun injectedLanguages(host: PsiElement): List<String> {
        val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host) ?: return emptyList()
        return injected.mapNotNull { (it.first as? PsiFile)?.language?.id }
    }
}
