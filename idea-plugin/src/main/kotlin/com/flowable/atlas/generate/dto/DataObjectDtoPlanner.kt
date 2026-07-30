package com.flowable.atlas.generate.dto

import com.flowable.atlas.intention.DataObjectBeanGenerator

/**
 * The naming rules behind the DTO generator: class name, per-app package segment and target path.
 * Pure (no PSI, no VFS) so the rules are unit-testable and shared by the bulk dialog and the
 * Alt-Enter intention — the two must propose the same default name for the same data object.
 */
object DataObjectDtoPlanner {

    /** Java's reserved words plus the literals — a package segment may not be any of them. */
    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "true", "false", "null", "_",
    )

    private val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    /**
     * The default class name for a data object: the model name (falling back to the key) in
     * PascalCase — [DataObjectBeanGenerator.classNameFor], the derivation the intention has always
     * used — plus [suffix]. The suffix is never doubled when the model name already ends in it, so a
     * data object literally named "Customer DTO" does not become `CustomerDtoDto`.
     */
    fun defaultClassName(modelName: String?, key: String, suffix: String): String {
        val base = DataObjectBeanGenerator.classNameFor(modelName, key)
        val trimmed = suffix.trim()
        if (trimmed.isEmpty() || base.endsWith(trimmed, ignoreCase = true)) return base
        return base + trimmed
    }

    /**
     * A model key rendered as one lower-case Java package segment: `DEMO-App v2` → `demoappv2`.
     * Empty or unusable input yields `app`, and a segment that would be a keyword or start with a
     * digit is prefixed with `_`.
     */
    fun packageSegment(appKey: String): String {
        val cleaned = appKey.filter { it.isLetterOrDigit() }.lowercase()
        if (cleaned.isEmpty()) return "app"
        if (cleaned.first().isDigit() || cleaned in JAVA_KEYWORDS) return "_$cleaned"
        return cleaned
    }

    /** [basePackage] plus the app segment when [perApp]; blank base and blank app both collapse away. */
    fun packageFor(basePackage: String, appKey: String?, perApp: Boolean): String {
        val base = basePackage.trim().trim('.')
        if (!perApp || appKey.isNullOrBlank()) return base
        val segment = packageSegment(appKey)
        return if (base.isEmpty()) segment else "$base.$segment"
    }

    /** Source-root-relative path of the generated file, e.g. `com/acme/dto/CustomerDto.java`. */
    fun targetPath(packageName: String, className: String): String {
        val dir = packageName.trim().trim('.').replace('.', '/')
        return if (dir.isEmpty()) "$className.java" else "$dir/$className.java"
    }

    /** True when every dot-separated segment of [packageName] is a legal Java identifier (blank is legal). */
    fun isValidPackage(packageName: String): Boolean {
        val trimmed = packageName.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.startsWith('.') || trimmed.endsWith('.') || trimmed.contains("..")) return false
        return trimmed.split('.').all { it.matches(IDENTIFIER) && it !in JAVA_KEYWORDS }
    }

    /** True when [className] is a legal Java class name. */
    fun isValidClassName(className: String): Boolean {
        val trimmed = className.trim()
        return trimmed.matches(IDENTIFIER) && trimmed !in JAVA_KEYWORDS
    }
}
