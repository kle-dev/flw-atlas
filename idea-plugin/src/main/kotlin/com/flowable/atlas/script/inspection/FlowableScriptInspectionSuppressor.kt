package com.flowable.atlas.script.inspection

import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.script.ScriptContext
import com.flowable.atlas.script.completion.ScriptScope
import com.flowable.atlas.script.inject.ScriptInjectionSupport
import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlText

/**
 * Silences the Groovy/JS "unresolved" inspections inside Flowable script bodies: `execution`,
 * `task`, `flw` & co. are dynamic engine bindings the language PSI cannot resolve, so
 * "No candidates found for method call execution" / gray-unresolved noise is guaranteed and wrong
 * there. Scoped tightly — only the playground field (context-stamped) and injected Flowable script
 * fragments; only the unresolved-reference tool ids. Real syntax errors are parser-level and stay;
 * the bindings-catalog validation covers the member typos these inspections never could.
 *
 * Platform API only (no Groovy/JS classes) — consistent with the plugin's no-language-plugin-
 * dependency rule; registered for `language="any"` and gating itself cheaply.
 */
class FlowableScriptInspectionSuppressor : InspectionSuppressor {

    private val suppressedToolIds = setOf(
        // Groovy
        "GrUnresolvedAccess", "GroovyAssignabilityCheck", "GroovyUntypedAccess",
        // JavaScript (Ultimate)
        "JSUnresolvedReference", "JSUnresolvedVariable", "JSUnresolvedFunction",
        "JSUnknownGlobalSymbol", "JSValidateTypes",
    )

    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        if (toolId !in suppressedToolIds) return false
        val file = element.containingFile ?: return false
        if (file.getUserData(ScriptScope.CONTEXT_KEY) != null) return true
        val host = InjectedLanguageManager.getInstance(file.project).getInjectionHost(file) ?: return false
        val vFile = host.containingFile?.viewProvider?.virtualFile ?: return false
        val type = ModelFiles.typeOf(vFile) ?: return false
        return when {
            host is XmlText -> ScriptInjectionSupport.scriptContextOf(host, type) != ScriptContext.UNKNOWN ||
                ScriptInjectionSupport.scriptFormatOf(host) != null
            host is JsonStringLiteral && type == ModelType.ACTION -> true
            else -> false
        }
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> =
        SuppressQuickFix.EMPTY_ARRAY
}
