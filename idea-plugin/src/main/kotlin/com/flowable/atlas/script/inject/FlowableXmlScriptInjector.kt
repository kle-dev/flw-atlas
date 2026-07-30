package com.flowable.atlas.script.inject

import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelType
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.xml.XmlText

/**
 * Injects the IDE's real Groovy / JavaScript language into script bodies of BPMN/CMMN model XML —
 * `<scriptTask><script>`, listener `<flowable:script>` and the CMMN script-task field — so users get
 * genuine compiler-grade syntax errors, highlighting and fragment editing right in the model file.
 * A missing/unknown `scriptFormat` (or an absent language plugin) injects nothing.
 */
class FlowableXmlScriptInjector : MultiHostInjector {

    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(XmlText::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        if (context !is XmlText) return
        val host = context as? PsiLanguageInjectionHost ?: return
        val vFile = context.containingFile?.viewProvider?.virtualFile ?: return
        val type = ModelFiles.typeOf(vFile)
        if (type != ModelType.PROCESS && type != ModelType.CASE) return
        val language = ScriptInjectionSupport.scriptLanguage(ScriptInjectionSupport.scriptFormatOf(context))
            ?: return
        ScriptInjectionSupport.inject(registrar, host, language)
    }
}
