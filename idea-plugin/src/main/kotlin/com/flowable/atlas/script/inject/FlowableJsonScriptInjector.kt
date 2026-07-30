package com.flowable.atlas.script.inject

import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelType
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost

/**
 * Injects the real script language into an action bot's code: the `config.scriptInfo.script` string
 * of an `.action` model (or Design `action-models` JSON), with the language taken from the sibling
 * `scriptInfo.language` property. JSON escape decoding (`\n`, `\"`) is the host escaper's job.
 */
class FlowableJsonScriptInjector : MultiHostInjector {

    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(JsonStringLiteral::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        if (context !is JsonStringLiteral) return
        val host = context as? PsiLanguageInjectionHost ?: return
        val vFile = context.containingFile?.viewProvider?.virtualFile ?: return
        if (ModelFiles.typeOf(vFile) != ModelType.ACTION) return
        val prop = context.parent as? JsonProperty ?: return
        if (prop.name != "script" || prop.value !== context) return
        val scriptInfo = prop.parent as? JsonObject ?: return
        if ((scriptInfo.parent as? JsonProperty)?.name != "scriptInfo") return
        val declared = (scriptInfo.findProperty("language")?.value as? JsonStringLiteral)?.value
        val language = ScriptInjectionSupport.scriptLanguage(declared) ?: return
        ScriptInjectionSupport.inject(registrar, host, language)
    }
}
