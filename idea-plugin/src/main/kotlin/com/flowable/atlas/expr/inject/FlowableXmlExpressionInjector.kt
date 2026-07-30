package com.flowable.atlas.expr.inject

import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.script.inject.ScriptInjectionSupport
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlText

/**
 * Injects the Flowable backend expression language into `${…}` / `#{…}` fragments in BPMN/CMMN/DMN
 * model XML — attribute values (`flowable:assignee="${…}"`, timers, …) and element text
 * (`<conditionExpression>${…}</conditionExpression>`, CMMN `<condition>`). Scoped to XML model files
 * so ordinary XML is untouched.
 */
class FlowableXmlExpressionInjector : MultiHostInjector {

    override fun elementsToInjectIn(): List<Class<out PsiElement>> =
        listOf(XmlAttributeValue::class.java, XmlText::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        if (context !is PsiLanguageInjectionHost) return
        val vFile = context.containingFile?.viewProvider?.virtualFile ?: return
        if (!ModelType.isXmlModel(vFile.name)) return
        // A script body that receives a real language injection (Groovy/JS) must not also get
        // backend-expr fragments for its GString `${…}` interpolation — two injectors overlapping on
        // one host. Gated on *language resolution*, not "is a script": `juel` bodies and script
        // bodies whose language plugin is absent keep the `${…}` injection they have today.
        if (context is XmlText &&
            ScriptInjectionSupport.scriptLanguage(ScriptInjectionSupport.scriptFormatOf(context)) != null) return
        ExpressionInjectionSupport.inject(registrar, context, setOf(ExpressionDialect.BACKEND))
    }
}
