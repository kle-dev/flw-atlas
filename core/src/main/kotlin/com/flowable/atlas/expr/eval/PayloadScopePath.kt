package com.flowable.atlas.expr.eval

/**
 * A user-selected location inside a form payload — the node an expression should be evaluated *at*,
 * as if it lived on a component inside the subform/list bound to that node (see [PayloadScopes]).
 * Rendered and parsed in the compact `orders[2].items[0]` form; keys that don't lex as plain
 * identifiers are bracket-quoted (`["a.b"]`). The empty path is the payload root. Only the string
 * form is persisted (workspace state) — the typed path lives between parse and evaluation.
 */
data class PayloadScopePath(val segments: List<Segment>) {

    sealed interface Segment {
        data class Key(val name: String) : Segment
        data class Index(val i: Int) : Segment
    }

    val isRoot: Boolean get() = segments.isEmpty()

    fun format(): String = buildString {
        for (s in segments) when (s) {
            is Segment.Key ->
                if (PLAIN_KEY.matches(s.name)) {
                    if (isNotEmpty()) append('.')
                    append(s.name)
                } else {
                    append("[\"").append(s.name.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"]")
                }
            is Segment.Index -> append('[').append(s.i).append(']')
        }
    }

    sealed interface ParseResult {
        data class Ok(val path: PayloadScopePath) : ParseResult
        data class Err(val message: String) : ParseResult
    }

    companion object {
        val ROOT = PayloadScopePath(emptyList())
        private val PLAIN_KEY = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

        /** Blank → [ROOT]. Grammar: `key`, `.key`, `[digits]`, `["quoted key"]` (also `'…'`), concatenated. */
        fun parse(text: String): ParseResult {
            val t = text.trim()
            if (t.isEmpty()) return ParseResult.Ok(ROOT)
            val segments = ArrayList<Segment>()
            var i = 0
            fun err(msg: String) = ParseResult.Err("Invalid scope path at offset $i: $msg")
            while (i < t.length) {
                when {
                    t[i] == '.' -> {
                        if (segments.isEmpty()) return err("a path cannot start with '.'")
                        i++
                        val start = i
                        if (i >= t.length || !isIdentStart(t[i])) return err("expected a key after '.'")
                        while (i < t.length && isIdentPart(t[i])) i++
                        segments += Segment.Key(t.substring(start, i))
                    }
                    t[i] == '[' -> {
                        i++
                        if (i < t.length && (t[i] == '"' || t[i] == '\'')) {
                            val quote = t[i]
                            i++
                            val key = StringBuilder()
                            while (i < t.length && t[i] != quote) {
                                if (t[i] == '\\' && i + 1 < t.length) i++
                                key.append(t[i])
                                i++
                            }
                            if (i >= t.length) return err("unterminated quoted key")
                            i++
                            if (i >= t.length || t[i] != ']') return err("expected ']' after the quoted key")
                            i++
                            segments += Segment.Key(key.toString())
                        } else {
                            val start = i
                            while (i < t.length && t[i].isDigit()) i++
                            if (start == i) return err("expected an index or a quoted key inside '[…]'")
                            if (i >= t.length || t[i] != ']') return err("expected ']' after the index")
                            val idx = t.substring(start, i).toIntOrNull() ?: return err("index is too large")
                            i++
                            segments += Segment.Index(idx)
                        }
                    }
                    segments.isEmpty() && isIdentStart(t[i]) -> {
                        val start = i
                        while (i < t.length && isIdentPart(t[i])) i++
                        segments += Segment.Key(t.substring(start, i))
                    }
                    else -> return err("unexpected character '${t[i]}'")
                }
            }
            return ParseResult.Ok(PayloadScopePath(segments))
        }

        private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c == '$'
        private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
    }
}
