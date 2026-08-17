package com.flowable.atlas.generate.dto

import com.flowable.atlas.generate.NamePattern
import com.flowable.atlas.intention.DataObjectBeanGenerator

/**
 * Pure (no `com.intellij.*`, no I/O) class-name builder for the "Generate → Data-Object DTOs" dialog —
 * the DTO counterpart of [com.flowable.atlas.generate.liquibase.LiquibaseFileNamePattern]: turns a
 * user-authored token pattern into the Java class name a DTO is written under.
 *
 * The pipeline is [NamePattern.render] (substitute `{token}`) → [NamePattern.applyRename] (an optional
 * regex find/replace for transformations tokens can't express) → [toIdentifier] (drop what a Java
 * identifier may not contain). Every token is itself already identifier-safe — PascalCase, derived the
 * way [DataObjectBeanGenerator.classNameFor] has always derived a class name — so composing tokens can
 * only ever yield a legal name; [toIdentifier] guards the literal text the user typed around them.
 *
 * The default `{name}{suffix}` reproduces the historical name (model name in PascalCase + the
 * configured suffix), which is why [DataObjectDtoPlanner.defaultClassName] delegates here.
 */
object DtoClassNamePattern {

    /** The default pattern — the model name plus the configured suffix, i.e. the pre-pattern behavior. */
    const val DEFAULT_PATTERN = "{name}{suffix}"

    /** The tokens a pattern may reference, in the order shown to the user. */
    val TOKENS: List<String> = listOf("name", "shortName", "key", "app", "suffix")

    /** The values a single DTO's class name is rendered from. Unknown/empty tokens render as `""`. */
    data class Tokens(
        val name: String,
        val shortName: String,
        val key: String,
        val app: String = "",
        val suffix: String = "",
    ) {
        fun asMap(): Map<String, String> = mapOf(
            "name" to name,
            "shortName" to shortName,
            "key" to key,
            "app" to app,
            "suffix" to suffix,
        )
    }

    /**
     * The [Tokens] for a data object: `{name}` from [modelName] (falling back to [key]), `{shortName}`
     * from [stripKeyPrefix], `{key}` and `{app}` from [key] / [appKey], each in PascalCase, plus
     * `{suffix}` from [suffix].
     *
     * `{suffix}` derives to `""` when the name already ends in it, so the default pattern never doubles
     * it: a data object literally named "Customer DTO" stays `CustomerDTO`, not `CustomerDTODto`. A DTO
     * that belongs to no app derives `{app}` to `""`.
     */
    fun deriveTokens(key: String, modelName: String?, appKey: String?, suffix: String): Tokens {
        val name = DataObjectBeanGenerator.classNameFor(modelName, key)
        val keyToken = DataObjectBeanGenerator.classNameFor(null, key)
        val trimmedSuffix = suffix.trim()
        return Tokens(
            name = name,
            shortName = stripKeyPrefix(name, keyToken),
            key = keyToken,
            app = appKey?.takeIf { it.isNotBlank() }?.let { DataObjectBeanGenerator.classNameFor(null, it) }.orEmpty(),
            suffix = if (name.endsWith(trimmedSuffix, ignoreCase = true)) "" else trimmedSuffix,
        )
    }

    /**
     * [name] without the model key Design projects habitually prefix their model names with, so
     * `DEMO-D009 Pod Member` yields `PodMember` instead of `DEMOD009PodMember`. Two rules, in order:
     *
     *  1. the [key] itself, when [name] starts with it — the exact, unambiguous case;
     *  2. otherwise a leading run of capitals/digits **containing at least one digit** and followed by a
     *     `Word`-shaped segment. This catches the key written differently from the key model — an
     *     unpadded `DEMO-D9 Document Type` against key `DEMO-D009` — while a name that merely opens with
     *     an acronym (`IBANCheck`, no digit) is left alone.
     *
     * Never returns `""`: a name that *is* its key (an unnamed model) has no `Word` segment to keep and
     * is returned unchanged, so `{shortName}` always renders something.
     */
    fun stripKeyPrefix(name: String, key: String): String {
        if (key.isNotEmpty() && name.length > key.length && name.startsWith(key)) return name.removePrefix(key)
        return KEY_PREFIX.find(name)?.let { name.substring(it.value.length) } ?: name
    }

    /**
     * Full pipeline: render [pattern] (blank → [DEFAULT_PATTERN]), apply the optional
     * [renameFind]→[renameReplace] regex, reduce to identifier characters.
     *
     * A malformed [renameFind] / [renameReplace] is ignored (the un-renamed name is returned) rather
     * than thrown: the dialog validates and reports the bad regex, and a stale one left in the project
     * settings must never break the Alt-Enter intention. The result can still be empty or start with a
     * digit — the caller validates it with [DataObjectDtoPlanner.isValidClassName] and shows the row.
     */
    fun className(pattern: String, tokens: Tokens, renameFind: String = "", renameReplace: String = ""): String {
        val rendered = NamePattern.render(pattern.ifBlank { DEFAULT_PATTERN }, tokens.asMap())
        val renamed = try {
            NamePattern.applyRename(rendered, renameFind, renameReplace)
        } catch (e: RuntimeException) {
            rendered
        }
        return toIdentifier(renamed)
    }

    /** [base] reduced to the characters a Java identifier may contain; everything else is dropped. */
    fun toIdentifier(base: String): String = base.replace(NON_IDENTIFIER, "")

    private val NON_IDENTIFIER = Regex("[^A-Za-z0-9_$]")

    /** A leading key-shaped run: capitals/digits with at least one digit, before a `Word` segment. */
    private val KEY_PREFIX = Regex("^[A-Z0-9]*\\d[A-Z0-9]*(?=[A-Z][a-z])")
}
