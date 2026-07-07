package com.flowable.atlas.expr.inject

import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.model.ModelType
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
        ExpressionInjectionSupport.inject(registrar, context, setOf(ExpressionDialect.BACKEND))
    }
}
