package com.flowable.atlas.generate.liquibase

/**
 * Pure (no `com.intellij.*`, no I/O) filename builder for the "Generate → Liquibase" dialog: turns a
 * user-authored token pattern into the `<base>.changelog.xml` name a changelog is written under.
 *
 * The pipeline is [render] (substitute `{token}`) → [applyRename] (an optional regex find/replace for
 * transformations tokens can't express) → [toFileName] (reduce to filename-safe characters + suffix).
 * A blank pattern or the default `{key}` reproduces the historical `<sanitized-key>.changelog.xml`.
 *
 * Kept pure so the derivation rules (`{servicePrefix}` / `{serviceNo}`) and the regex step are
 * unit-testable without an IDE fixture — the dialog is the only place that reads project state.
 */
object LiquibaseFileNamePattern {

    /** The suffix every changelog file carries; the pattern produces only the base before it. */
    const val SUFFIX = ".changelog.xml"

    /** The default pattern — the data-object / changelog key, i.e. the pre-dialog behavior. */
    const val DEFAULT_PATTERN = "{key}"

    /** The tokens a pattern may reference, in the order shown to the user. */
    val TOKENS: List<String> = listOf("key", "name", "service", "servicePrefix", "serviceNo", "table")

    /** The values a single changelog's filename is rendered from. Unknown/empty tokens render as `""`. */
    data class Tokens(
        val key: String,
        val name: String,
        val service: String = "",
        val servicePrefix: String = "",
        val serviceNo: String = "",
        val table: String = "",
    ) {
        fun asMap(): Map<String, String> = mapOf(
            "key" to key,
            "name" to name,
            "service" to service,
            "servicePrefix" to servicePrefix,
            "serviceNo" to serviceNo,
            "table" to table,
        )
    }

    /**
     * The [Tokens] for a data object: [key] and [name] verbatim (the caller passes the per-row name,
     * defaulting to [slug] of the display name), plus `{service}`/`{servicePrefix}`/`{serviceNo}`
     * derived from [serviceKey] and `{table}` from [tableName]. Missing inputs derive to `""`.
     */
    fun deriveTokens(key: String, name: String, serviceKey: String?, tableName: String?): Tokens {
        val svc = serviceKey.orEmpty()
        return Tokens(
            key = key,
            name = name,
            service = svc,
            servicePrefix = servicePrefix(svc),
            serviceNo = serviceNo(svc),
            table = tableName.orEmpty(),
        )
    }

    /** [pattern] with each `{token}` replaced by its [tokens] value (unknown tokens → `""`). */
    fun render(pattern: String, tokens: Map<String, String>): String =
        TOKEN_REF.replace(pattern) { m -> tokens[m.groupValues[1]] ?: "" }

    /**
     * [base] with [find]→[replace] applied (Kotlin regex replacement, so `$1` group refs work). A blank
     * [find] is a no-op. Throws [java.util.regex.PatternSyntaxException] when [find] is not a valid
     * regex — the dialog validates and reports it before ever calling this on user input.
     */
    fun applyRename(base: String, find: String, replace: String): String =
        if (find.isBlank()) base else Regex(find).replace(base, replace)

    /** [base] reduced to filename-safe characters, with [SUFFIX] appended. */
    fun toFileName(base: String): String = base.replace(UNSAFE, "-") + SUFFIX

    /** Full pipeline: [render] → [applyRename] → [toFileName]. */
    fun fileName(pattern: String, tokens: Tokens, renameFind: String = "", renameReplace: String = ""): String =
        toFileName(applyRename(render(pattern.ifBlank { DEFAULT_PATTERN }, tokens.asMap()), renameFind, renameReplace))

    /** A model display name reduced to a lowercase, dash-separated slug ("Pod Member" → "pod-member"). */
    fun slug(s: String): String = s.trim().lowercase().replace(NON_ALNUM, "-").trim('-')

    // ---- token derivation -------------------------------------------------------------------

    /** The service key with its trailing `[-_]?<letters><digits>` id segment removed (`KYC-S009` → `KYC`). */
    private fun servicePrefix(serviceKey: String): String =
        if (serviceKey.isBlank()) "" else serviceKey.replace(TRAILING_ID, "")

    /** The last digit run of the service key, leading zeros stripped (`KYC-S009` → `9`; none → `""`). */
    private fun serviceNo(serviceKey: String): String {
        val digits = DIGIT_RUN.findAll(serviceKey).lastOrNull()?.value ?: return ""
        return digits.trimStart('0').ifEmpty { "0" }
    }

    private val TOKEN_REF = Regex("\\{(\\w+)}")
    private val TRAILING_ID = Regex("[-_]?[A-Za-z]*\\d+$")
    private val DIGIT_RUN = Regex("\\d+")
    private val UNSAFE = Regex("[^A-Za-z0-9._-]")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
}
