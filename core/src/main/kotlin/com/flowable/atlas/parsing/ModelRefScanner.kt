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

    /**
     * What a model's expressions do with a name, precisely enough to say *which* Java symbol they use:
     * every `root.member` a `${…}`/`#{…}` names (as `root#member`), and every root — `orderService` in
     * `${orderService.place(x)}` and in `${orderService}`. A method is used where one of its class's beans
     * is the root and the method the member; that `getId` appears in some expression says nothing about
     * which class's `getId` it is — 92 of 168 "referenced from models" markers on real projects were that.
     */
    fun scanMembers(text: String, members: MutableSet<String>, roots: MutableSet<String>) {
        for (match in EXPRESSION.findAll(text)) {
            val body = STRING_LITERAL.replace(match.groupValues[1], " ")
            for (m in ROOT_MEMBER.findAll(body)) {
                roots.add(m.groupValues[1])
                m.groups[2]?.value?.let { members.add("${m.groupValues[1]}#$it") }
            }
        }
    }

    private val STRING_LITERAL = Regex("'[^']*'|\"[^\"]*\"")
    // a root: not after `.` (a member) or an identifier char, not an EL namespace (`ns:fn(`) or a call
    private val ROOT_MEMBER = Regex("(?<![\\w.$])([A-Za-z_]\\w*)(?!\\s*[(:\\w])(?:\\s*\\.\\s*([A-Za-z_]\\w*))?")
}
