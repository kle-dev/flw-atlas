package com.flowable.atlas.expr

/**
 * THE wrapper-stripping helper for expression bodies — validator, grounding and the frontend
 * evaluator all accept an optionally-wrapped body (`${…}` / `#{…}` / `{{…}}`) and need the inner
 * text; keeping one implementation guarantees they agree on what counts as "wrapped".
 */
object ExprWrappers {

    val ALL = listOf("{{" to "}}", "\${" to "}", "#{" to "}")
    val BACKEND = listOf("\${" to "}", "#{" to "}")
    val FRONTEND = listOf("{{" to "}}")

    /**
     * If [body] is entirely wrapped in one of [wrappers], return the inner text and its start
     * offset in [body] (for shifting problem ranges back); otherwise ([body], 0). Injected
     * fragments are already delimiter-free, so they pass through unchanged.
     */
    fun stripOuter(body: String, wrappers: List<Pair<String, String>> = ALL): Pair<String, Int> {
        val start = body.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return body to 0
        val end = body.indexOfLast { !it.isWhitespace() } + 1
        val core = body.substring(start, end)
        for ((open, close) in wrappers) {
            if (core.length >= open.length + close.length && core.startsWith(open) && core.endsWith(close)) {
                val innerStart = start + open.length
                val innerEnd = end - close.length
                return body.substring(innerStart, innerEnd) to innerStart
            }
        }
        return body to 0
    }
}
