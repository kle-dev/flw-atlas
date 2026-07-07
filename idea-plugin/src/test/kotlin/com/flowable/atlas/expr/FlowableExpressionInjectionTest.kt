package com.flowable.atlas.expr

import com.flowable.atlas.expr.lang.FlowableBackendExprLanguage
import com.flowable.atlas.expr.lang.FlowableFrontendExprLanguage
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FlowableExpressionInjectionTest : BasePlatformTestCase() {

    fun testFormFilesAreTreatedAsJson() {
        val file = myFixture.configureByText("f.form", """{"key":"F1","name":"Form"}""")
        assertEquals("JSON", file.fileType.name)
    }

    fun testBackendExpressionInjectedIntoBpmnAttribute() {
        myFixture.configureByText(
            "task.bpmn20.xml",
            """<?xml version="1.0"?>
               <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                            xmlns:flowable="http://flowable.org/bpmn">
                 <process id="P1">
                   <userTask id="t" flowable:assignee="${'$'}{ execution }"/>
                 </process>
               </definitions>""",
        )
        val host = PsiTreeUtil.findChildrenOfType(myFixture.file, XmlAttributeValue::class.java)
            .firstOrNull { it.value.contains("execution") }
        assertNotNull("attribute host must be found", host)
        assertTrue("backend expr injected", injectedLanguages(host!!).contains(FlowableBackendExprLanguage.id))
    }

    fun testFrontendExpressionInjectedIntoFormJson() {
        myFixture.configureByText(
            "greeting.form",
            """{ "key": "F1", "label": "Hi {{ flw.sum(items) }}" }""",
        )
        val host = PsiTreeUtil.findChildrenOfType(myFixture.file, JsonStringLiteral::class.java)
            .firstOrNull { it.text.contains("flw") }
        assertNotNull("json string host must be found", host)
        assertTrue("frontend expr injected", injectedLanguages(host!!).contains(FlowableFrontendExprLanguage.id))
    }

    private fun injectedLanguages(host: com.intellij.psi.PsiElement): List<String> {
        val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host) ?: return emptyList()
        return injected.mapNotNull { (it.first as? PsiFile)?.language?.id }
    }
}
