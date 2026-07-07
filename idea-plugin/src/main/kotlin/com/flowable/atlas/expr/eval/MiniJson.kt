package com.flowable.atlas.expr.eval

/**
 * A tiny, dependency-free JSON reader/writer for the frontend expression playground's pasted payload.
 * Objects → [LinkedHashMap], arrays → [ArrayList], numbers → [Double] (JS-like), strings, booleans, null.
 * Deliberately lenient enough for hand-pasted payloads but strict about structure.
 */
object MiniJson {

    class JsonException(message: String) : RuntimeException(message)

    fun parse(text: String): Any? {
        val p = P(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw JsonException("Unexpected trailing content at ${p.pos}")
        return v
    }

    /** Compact JSON rendering of an evaluated value (for showing results). */
    fun stringify(value: Any?): String = buildString { write(value, this) }

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(v, sb)
            is Boolean -> sb.append(v.toString())
            is Double -> sb.append(if (v == v.toLong().toDouble() && !v.isInfinite()) v.toLong().toString() else v.toString())
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(','); first = false
                    writeString(k.toString(), sb); sb.append(':'); write(value, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (e in v) { if (!first) sb.append(','); first = false; write(e, sb) }
                sb.append(']')
            }
            else -> writeString(v.toString(), sb)
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
        sb.append('"')
    }

    private class P(val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

        fun readValue(): Any? {
            skipWs()
            if (atEnd()) throw JsonException("Unexpected end of input")
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                else -> if (c == '-' || c.isDigit()) readNumber() else throw JsonException("Unexpected character '$c' at $pos")
            }
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (peek() != '"') throw JsonException("Expected property name at $pos")
                val key = readString()
                skipWs()
                if (peek() != ':') throw JsonException("Expected ':' at $pos")
                pos++
                map[key] = readValue()
                skipWs()
                when (peek()) {
                    ',' -> { pos++; continue }
                    '}' -> { pos++; return map }
                    else -> throw JsonException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (peek() == ']') { pos++; return list }
            while (true) {
                list += readValue()
                skipWs()
                when (peek()) {
                    ',' -> { pos++; continue }
                    ']' -> { pos++; return list }
                    else -> throw JsonException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            val sb = StringBuilder()
            pos++ // opening quote
            while (pos < s.length) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) throw JsonException("Unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("Bad unicode escape")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4
                            }
                            else -> throw JsonException("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw JsonException("Unterminated string")
        }

        private fun readNumber(): Double {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: throw JsonException("Invalid number at $start")
        }

        private fun readBoolean(): Boolean = when {
            s.startsWith("true", pos) -> { pos += 4; true }
            s.startsWith("false", pos) -> { pos += 5; false }
            else -> throw JsonException("Invalid literal at $pos")
        }

        private fun readNull(): Any? {
            if (s.startsWith("null", pos)) { pos += 4; return null }
            throw JsonException("Invalid literal at $pos")
        }

        private fun peek(): Char? = if (pos < s.length) s[pos] else null
    }
}
