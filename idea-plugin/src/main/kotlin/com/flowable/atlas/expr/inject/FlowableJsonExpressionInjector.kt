package com.flowable.atlas.expr.inject

import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelType
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost

/**
 * Injects the Flowable frontend expression language into `{{…}}` fragments inside Flowable form model
 * JSON (`.form`, or Design `form-models` JSON when workspace indexing is on). Dialect is chosen by
 * delimiter, so a stray `${…}` in form JSON would route to the backend language instead.
 *
 * Requires `.form` to be recognized as JSON — see the `<fileType>` association in plugin.xml.
 */
class FlowableJsonExpressionInjector : MultiHostInjector {

    override fun elementsToInjectIn(): List<Class<out PsiElement>> =
        listOf(JsonStringLiteral::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        if (context !is JsonStringLiteral) return
        val host = context as? PsiLanguageInjectionHost ?: return
        val vFile = context.containingFile?.viewProvider?.virtualFile ?: return
        if (ModelFiles.typeOf(vFile) != ModelType.FORM) return
        ExpressionInjectionSupport.inject(
            registrar,
            host,
            setOf(ExpressionDialect.FRONTEND, ExpressionDialect.BACKEND),
        )
    }
}
