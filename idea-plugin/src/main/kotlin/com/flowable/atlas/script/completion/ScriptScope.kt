package com.flowable.atlas.script.completion

import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.script.ScriptContext
import com.flowable.atlas.script.inject.ScriptInjectionSupport
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlText

/**
 * User-data key that lets the Script Playground tell completion which [ScriptContext]'s bindings
 * to offer (there is no injection host to derive it from in a scratch field) — the script twin of
 * [com.flowable.atlas.expr.ExpressionScope].
 */
object ScriptScope {
    val CONTEXT_KEY: Key<ScriptContext> = Key.create("com.flowable.atlas.script.playgroundContext")

    /** Marks the playground's scratch VirtualFile so `import`s resolve against the whole project
     *  (see `ScriptPlaygroundResolveScopeProvider`) — a LanguageTextField file has no module. */
    val PLAYGROUND_FILE: Key<Boolean> = Key.create("com.flowable.atlas.script.playgroundFile")

    /** The script context of [file]: the playground's stamp, or — for an injected fragment — the
     *  Flowable script body it lives in. Null when the file is neither. */
    fun contextOf(file: PsiFile): ScriptContext? {
        file.getUserData(CONTEXT_KEY)?.let { return it }
        val host = InjectedLanguageManager.getInstance(file.project).getInjectionHost(file) ?: return null
        val vFile = host.containingFile?.viewProvider?.virtualFile ?: return null
        val type = ModelFiles.typeOf(vFile) ?: return null
        return when {
            host is XmlText -> ScriptInjectionSupport.scriptContextOf(host, type)
                .takeIf { it != ScriptContext.UNKNOWN }
            host is JsonStringLiteral && type == ModelType.ACTION -> ScriptContext.ACTION_BOT
            else -> null
        }
    }
}
