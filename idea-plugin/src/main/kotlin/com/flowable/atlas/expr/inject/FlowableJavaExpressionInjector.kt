package com.flowable.atlas.expr.inject

import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.settings.FlowableAtlasSettings
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiLiteralExpression

/**
 * Opt-in: injects the Flowable backend expression language into Java String literals that carry a
 * `${…}` / `#{…}` expression. Off by default (see `injectJavaExpressions` in settings) because Java
 * strings holding `${…}` are not always Flowable expressions. Strings without a delimiter are a
 * no-op (the scanner finds nothing), so enabling it only lights up strings that already look like
 * expressions.
 */
class FlowableJavaExpressionInjector : MultiHostInjector {

    override fun elementsToInjectIn(): List<Class<out PsiElement>> =
        listOf(PsiLiteralExpression::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        if (!FlowableAtlasSettings.getInstance().injectJavaExpressions) return
        if (context !is PsiLiteralExpression || context.value !is String) return
        if (context !is PsiLanguageInjectionHost) return
        ExpressionInjectionSupport.inject(registrar, context, setOf(ExpressionDialect.BACKEND))
    }
}
