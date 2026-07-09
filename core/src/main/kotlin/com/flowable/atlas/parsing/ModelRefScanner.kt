package com.flowable.atlas.parsing

/**
 * Extracts the Java symbols a model file refers to, so they can be treated as implicitly used:
 *  - identifiers inside EL expressions `${...}` / `#{...}` (bean names, method names);
 *  - values of `class` / `delegateExpression` / `expression` attributes (delegate FQNs or expressions).
 *
 * Deliberately loose — over-collecting only means an occasional genuinely-unused symbol isn't
 * flagged, which is harmless. Pure text scanning (no I/O, no IntelliJ).
 */
object ModelRefScanner {

    private val EXPRESSION = Regex("[#$]\\{([^}]*)}")
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val CLASS_ATTR = Regex("(?:class|delegateExpression|expression)\\s*=\\s*\"([^\"]*)\"")

    fun scan(text: String, identifiers: MutableSet<String>, classFqns: MutableSet<String>) {
        for (match in EXPRESSION.findAll(text)) {
            collectIdentifiers(match.groupValues[1], identifiers)
        }
        for (match in CLASS_ATTR.findAll(text)) {
            val value = match.groupValues[1]
            when {
                value.contains("\${") || value.contains("#{") -> collectIdentifiers(value, identifiers)
                value.contains('.') -> {
                    classFqns.add(value)
                    identifiers.add(value.substringAfterLast('.'))
                }
                value.isNotBlank() -> identifiers.add(value)
            }
        }
    }

    private fun collectIdentifiers(expression: String, into: MutableSet<String>) {
        for (id in IDENTIFIER.findAll(expression)) into.add(id.value)
    }
}
