package com.flowable.atlas.script.toolwindow

import com.flowable.atlas.script.ScriptLanguages
import com.flowable.atlas.script.inject.ScriptInjectionSupport
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.fileTypes.UnknownFileType

/**
 * The Script Playground's language picker entries, and how each maps to a real IDE [Language].
 * Lookups are by ID at runtime with a plain-text fallback — a missing Groovy/JavaScript/Python
 * plugin means plain-text editing, never an error (the structural validation and the variable
 * analysis are language-plugin-independent). [ScriptInjectionSupport] itself stays untouched: the
 * model-file injection is deliberately Groovy/JS-only, the playground alone also tries Python.
 */
enum class PlaygroundScriptLanguage(val format: String, val display: String, private val extension: String) {
    GROOVY("groovy", "Groovy", "groovy"),
    JAVASCRIPT("javascript", "JavaScript", "js"),
    PYTHON("python", "Python", "py");

    /** The real IDE language when its plugin is present; [PlainTextLanguage] otherwise. */
    fun ideLanguage(): Language =
        ScriptInjectionSupport.scriptLanguage(format)
            ?: (if (this == PYTHON) Language.findLanguageByID("Python") else null)
            ?: PlainTextLanguage.INSTANCE

    /** The FileType driving the editor's lexer-based syntax coloring. */
    fun fileType(): FileType =
        ideLanguage().associatedFileType
            ?: FileTypeManager.getInstance().getFileTypeByExtension(extension).takeIf { it !is UnknownFileType }
            ?: PlainTextFileType.INSTANCE

    companion object {
        /** Any raw scriptFormat ("js", "nashorn", "jython", …) → the combo entry, via the :core family map. */
        fun fromFormat(format: String?): PlaygroundScriptLanguage = when (ScriptLanguages.family(format)) {
            ScriptLanguages.Family.JS -> JAVASCRIPT
            ScriptLanguages.Family.PYTHON -> PYTHON
            else -> GROOVY
        }
    }
}
