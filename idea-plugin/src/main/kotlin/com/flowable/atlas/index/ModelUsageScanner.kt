package com.flowable.atlas.index

/**
 * Locates, within a model file's text, the exact offset ranges where a Java symbol is referenced —
 * inside an EL expression `${...}` / `#{...}` (bean/method names) or a `class` / `delegateExpression`
 * / `expression` attribute (delegate FQN). Used by the "find usages in models" searcher.
 */
object ModelUsageScanner {

    private val EXPRESSION = Regex("[#$]\\{([^}]*)}")
    private val CLASS_ATTR = Regex("(?:class|delegateExpression|expression)\\s*=\\s*\"([^\"]*)\"")

    /** Absolute offset ranges (end-inclusive) in [text] where any of [names] is referenced. */
    fun findUsages(text: String, names: Set<String>): List<IntRange> {
        val wanted = names.filter { it.isNotBlank() }
        if (wanted.isEmpty()) return emptyList()

        val result = LinkedHashSet<IntRange>()
        val alternation = wanted.joinToString("|") { Regex.escape(it) }
        // A whole token: preceded by anything other than an identifier char (so `.doWork` and a
        // quoted FQN match) and not part of a longer identifier (so "Work" ≠ "doWork").
        val word = Regex("(?<![A-Za-z0-9_])(?:$alternation)(?![A-Za-z0-9_])")

        fun collect(fragment: String, fragmentStart: Int) {
            for (w in word.findAll(fragment)) {
                result.add((fragmentStart + w.range.first)..(fragmentStart + w.range.last))
            }
        }

        // EL expressions: identifiers / bean / method names.
        for (m in EXPRESSION.findAll(text)) collect(m.groupValues[1], m.range.first + 2) // past "${" / "#{"
        // class / delegateExpression / expression attribute values: delegate FQNs (and any EL inside).
        for (m in CLASS_ATTR.findAll(text)) {
            val value = m.groupValues[1]
            collect(value, m.range.first + m.value.indexOf(value))
        }
        return result.sortedBy { it.first }
    }
}
