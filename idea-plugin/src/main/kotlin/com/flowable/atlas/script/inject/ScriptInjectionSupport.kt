package com.flowable.atlas.script.inject

import com.flowable.atlas.model.ModelType
import com.flowable.atlas.script.ScriptContext
import com.flowable.atlas.script.ScriptLanguages
import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText

/**
 * Shared helper: resolve a Flowable `scriptFormat` to the IDE's real script language and inject the
 * whole host value as one fragment.
 *
 * Languages are looked up **by ID at runtime** ([Language.findLanguageByID]), never referenced as
 * classes — so this plugin needs no dependency, not even an optional one, on the Groovy or
 * JavaScript plugins. Groovy is bundled in every IDEA; JavaScript exists in Ultimate. Where the
 * language plugin is absent or disabled the lookup returns null and the script body simply stays a
 * plain string — never an error.
 */
object ScriptInjectionSupport {

    /** The IDE [Language] a scriptFormat names, or null (unknown format, JUEL, plugin absent). */
    fun scriptLanguage(format: String?): Language? = when (ScriptLanguages.family(format)) {
        ScriptLanguages.Family.GROOVY -> Language.findLanguageByID("Groovy")
        // the ES6 dialect first, so modern GraalJS-era syntax doesn't false-error; base JS as fallback
        ScriptLanguages.Family.JS ->
            Language.findLanguageByID("ECMAScript 6") ?: Language.findLanguageByID("JavaScript")
        else -> null
    }

    /**
     * The declared script format when [host] is a script body, else null. Matched on local names so
     * `flowable:` prefixes don't matter. The three XML shapes:
     *  1. BPMN `<scriptTask scriptFormat="…"><script>BODY` — format on the task;
     *  2. listener `<flowable:executionListener><flowable:script scriptFormat="…">BODY` — format on
     *     the script tag itself (fallback: the listener tag);
     *  3. CMMN `<task flowable:type="script" flowable:scriptFormat="…">` with BODY inside
     *     `<flowable:field name="script"><flowable:string>` (often CDATA).
     */
    fun scriptFormatOf(host: XmlText): String? {
        val parent = host.parentTag ?: return null
        return when (parent.localName) {
            "script" -> when ((parent.parent as? XmlTag)?.localName) {
                "scriptTask" -> attr(parent.parent as XmlTag, "scriptFormat")
                "executionListener", "taskListener", "planItemLifecycleListener" ->
                    attr(parent, "scriptFormat") ?: attr(parent, "language")
                        ?: attr(parent.parent as XmlTag, "scriptFormat")
                else -> null
            }
            "string" -> {
                val field = parent.parent as? XmlTag ?: return null
                if (field.localName != "field" || attr(field, "name") != "script") return null
                var task = field.parent as? XmlTag ?: return null
                if (task.localName == "extensionElements") task = task.parent as? XmlTag ?: return null
                if (attr(task, "type") != "script") return null
                attr(task, "scriptFormat")
            }
            else -> null
        }
    }

    /**
     * Which Flowable [ScriptContext] a script body host sits in — the completion/validation twin of
     * [scriptFormatOf], resolved from the same XML shapes plus the file's model type (a
     * `taskListener` binds different roots in BPMN vs CMMN).
     */
    fun scriptContextOf(host: XmlText, modelType: ModelType?): ScriptContext {
        val parent = host.parentTag ?: return ScriptContext.UNKNOWN
        return when (parent.localName) {
            "script" -> when ((parent.parent as? XmlTag)?.localName) {
                "scriptTask" -> ScriptContext.BPMN_SCRIPT_TASK
                "executionListener" -> ScriptContext.BPMN_EXECUTION_LISTENER
                "taskListener" -> if (modelType == ModelType.CASE) ScriptContext.CMMN_TASK_LISTENER
                    else ScriptContext.BPMN_TASK_LISTENER
                else -> ScriptContext.UNKNOWN   // planItemLifecycleListener: no script support anyway
            }
            "string" -> if (scriptFormatOf(host) != null || isCmmnScriptField(host))
                ScriptContext.CMMN_SCRIPT_TASK else ScriptContext.UNKNOWN
            else -> ScriptContext.UNKNOWN
        }
    }

    private fun isCmmnScriptField(host: XmlText): Boolean {
        val parent = host.parentTag ?: return false
        if (parent.localName != "string") return false
        val field = parent.parent as? XmlTag ?: return false
        return field.localName == "field" && attr(field, "name") == "script"
    }

    /** Whole-value single-place injection; CDATA/entity decoding is the host escaper's job. */
    fun inject(registrar: MultiHostRegistrar, host: PsiLanguageInjectionHost, language: Language) {
        if (!host.isValidHost) return
        val range = ElementManipulators.getValueTextRange(host)
        if (range.isEmpty || range.substring(host.text).isBlank()) return
        registrar.startInjecting(language)
        registrar.addPlace(null, null, host, range)
        registrar.doneInjecting()
    }

    private val XmlText.parentTag: XmlTag? get() = parent as? XmlTag

    private fun attr(tag: XmlTag, localName: String): String? =
        tag.attributes.firstOrNull { it.localName == localName }?.value?.trim()?.ifEmpty { null }
}
