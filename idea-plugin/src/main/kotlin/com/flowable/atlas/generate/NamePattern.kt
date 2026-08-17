package com.flowable.atlas.generate

/**
 * The token + regex-rename engine both bulk generators name their output with:
 * [com.flowable.atlas.generate.liquibase.LiquibaseFileNamePattern] (changelog file names) and
 * [com.flowable.atlas.generate.dto.DtoClassNamePattern] (DTO class names). Pure (no `com.intellij.*`,
 * no I/O) so the two dialogs share one substitution rule instead of drifting apart.
 *
 * What each generator adds on top is its own: which tokens exist, and how the rendered text is reduced
 * to something legal in its target (a file name vs. a Java identifier).
 */
object NamePattern {

    /** [pattern] with each `{token}` replaced by its [tokens] value; an unknown token renders as `""`. */
    fun render(pattern: String, tokens: Map<String, String>): String =
        TOKEN_REF.replace(pattern) { m -> tokens[m.groupValues[1]] ?: "" }

    /**
     * [base] with [find]→[replace] applied (Kotlin regex replacement, so `$1` group refs work). A blank
     * [find] is a no-op. Throws [java.util.regex.PatternSyntaxException] when [find] is not a valid
     * regex — callers validate user input first, or fall back to the un-renamed [base].
     */
    fun applyRename(base: String, find: String, replace: String): String =
        if (find.isBlank()) base else Regex(find).replace(base, replace)

    private val TOKEN_REF = Regex("\\{(\\w+)}")
}
