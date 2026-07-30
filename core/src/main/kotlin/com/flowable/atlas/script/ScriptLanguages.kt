package com.flowable.atlas.script

/** The script languages Flowable models declare, and how they group into scanner families. */
object ScriptLanguages {

    /** Script languages whose bare identifiers are scope variables (`juel` is an expression, not a script). */
    val SCRIPT_LANGS = setOf("groovy", "groovy-static", "javascript", "js", "ecmascript", "nashorn", "graal.js", "python", "jython")

    /** Everything a `scriptFormat` may legitimately name — the candidate set for the typo check. */
    val KNOWN_FORMATS: Set<String> = SCRIPT_LANGS + "juel"

    enum class Family { GROOVY, JS, PYTHON, JUEL, UNKNOWN }

    fun family(format: String?): Family = when (format?.trim()?.lowercase()) {
        "groovy", "groovy-static" -> Family.GROOVY
        "javascript", "js", "ecmascript", "nashorn", "graal.js" -> Family.JS
        "python", "jython" -> Family.PYTHON
        "juel" -> Family.JUEL
        else -> Family.UNKNOWN
    }
}
