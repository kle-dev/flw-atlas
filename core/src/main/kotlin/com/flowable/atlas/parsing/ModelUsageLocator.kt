package com.flowable.atlas.parsing

/**
 * Locates, within a model file's text, the exact offset ranges where a Java symbol is referenced —
 * inside an EL expression `${...}` / `#{...}` (bean/method names) or a `class` / `delegateExpression`
 * / `expression` attribute (delegate FQN). Used by the "find usages in models" searcher. Pure text
 * scanning (no I/O, no IntelliJ).
 */
object ModelUsageLocator {

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

    /**
     * Where a model uses one Java symbol, precisely: for a member ([members] non-empty), each `bean.member`
     * whose bean is one of [beans] — the range covers the member; for a class, each expression root that is
     * one of [beans], and each `class` attribute that names [fqn] exactly. A name that merely matches —
     * another class's `getId`, a variable `order` beside a class `Order`, `org.acme.lib.OrderDelegate` for
     * the project's own `com.example.OrderDelegate` — is not a usage.
     */
    fun findJavaUsages(text: String, beans: Set<String>, members: Set<String>, fqn: String?): List<IntRange> {
        val result = LinkedHashSet<IntRange>()
        val b = beans.filter { it.isNotBlank() }
        if (b.isNotEmpty()) {
            val beanAlt = b.joinToString("|") { Regex.escape(it) }
            val re = if (members.isNotEmpty()) {
                Regex("(?<![\\w.$])(?:$beanAlt)\\s*\\.\\s*(${members.joinToString("|") { Regex.escape(it) }})(?![\\w])")
            } else Regex("(?<![\\w.$])($beanAlt)(?![\\w(:])")
            for (m in EXPRESSION.findAll(text)) {
                val bodyStart = m.range.first + 2
                val body = blankLiterals(m.groupValues[1])
                for (w in re.findAll(body)) {
                    val g = w.groups[1] ?: continue
                    result.add((bodyStart + g.range.first)..(bodyStart + g.range.last))
                }
            }
        }
        if (fqn != null && members.isEmpty()) {
            for (m in CLASS_ATTR.findAll(text)) {
                val value = m.groupValues[1].trim()
                if (value == fqn) {
                    val at = m.range.first + m.value.indexOf(m.groupValues[1]) + m.groupValues[1].indexOf(value)
                    result.add(at..(at + value.length - 1))
                }
            }
        }
        return result.sortedBy { it.first }
    }

    /** String literals blanked in place (same length), so offsets still line up. */
    private fun blankLiterals(s: String): String = Regex("'[^']*'|\"[^\"]*\"").replace(s) { " ".repeat(it.value.length) }
}
