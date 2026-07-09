package com.flowable.atlas.model

/**
 * THE tiny, dependency-free JSON reader/writer of the plugin (indexer + expression playground —
 * a small recursive-descent parser avoids pulling in, or colliding with, the platform's JSON
 * libraries). Objects → [LinkedHashMap], arrays → [ArrayList], numbers → [Double] (JS-like),
 * strings, booleans, null.
 *
 * Two entry points with distinct contracts:
 *  - [parse] — strict: throws [JsonException] on malformed input or trailing content
 *    (playground payloads, Inspect responses — the user must see what is wrong);
 *  - [parseOrNull] — tolerant: best-effort read of one value, trailing content ignored,
 *    null on error (model files during indexing — a broken file must never break the scan).
 */
object MiniJson {

    class JsonException(message: String) : RuntimeException(message)

    fun parse(text: String): Any? {
        val p = P(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.atEnd()) p.fail("Extra data", p.pos)
        return v
    }

    fun parseOrNull(text: String): Any? =
        try {
            val p = P(text)
            p.skipWs()
            p.readValue()
        } catch (e: Exception) {
            null
        }

    /** Compact JSON rendering of an evaluated value (for showing results). */
    fun stringify(value: Any?): String = buildString { write(value, this) }

    /**
     * Pretty JSON rendering, matching Python's
     * `json.dumps(value, indent=[indent], ensure_ascii=False, default=list)` — the exact call the
     * standalone CLI's `--json` / `--all` graph.json output uses:
     *  - [indent]-space indent per nesting level; `": "` between key and value; items separated by
     *    `,\n`; empty objects/arrays inline (`{}` / `[]`);
     *  - strings escaped like JSON with non-ASCII kept literal (`ensure_ascii=False`);
     *  - [Set]s / [Iterable]s rendered as arrays (`default=list`);
     *  - integral [Number]s (Int/Long/…) without a decimal (`1`), [Double]/[Float] as Python floats
     *    (integral values keep one decimal, e.g. `1.0`).
     */
    fun stringify(value: Any?, indent: Int): String =
        buildString { writeIndented(value, this, indent, 0) }

    private fun writeIndented(v: Any?, sb: StringBuilder, unit: Int, level: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> writeJsonString(v, sb)
            is Boolean -> sb.append(v.toString())
            is Double -> sb.append(v.toString())          // integral Double -> "1.0" (Python float style)
            is Float -> sb.append(v.toDouble().toString())
            is Number -> sb.append(v.toString())           // Int/Long/Short/Byte/BigInteger -> no decimal
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                val childPad = " ".repeat(unit * (level + 1))
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(",\n"); first = false
                    sb.append(childPad); writeJsonString(k.toString(), sb); sb.append(": ")
                    writeIndented(value, sb, unit, level + 1)
                }
                sb.append('\n').append(" ".repeat(unit * level)).append('}')
            }
            is Iterable<*> -> {
                if (!v.iterator().hasNext()) { sb.append("[]"); return }
                sb.append("[\n")
                val childPad = " ".repeat(unit * (level + 1))
                var first = true
                for (e in v) {
                    if (!first) sb.append(",\n"); first = false
                    sb.append(childPad); writeIndented(e, sb, unit, level + 1)
                }
                sb.append('\n').append(" ".repeat(unit * level)).append(']')
            }
            else -> writeJsonString(v.toString(), sb)
        }
    }

    /** JSON string escaping matching Python's `json.dumps(..., ensure_ascii=False)`: escape the
     *  quote/backslash and control chars (`\b \t \n \f \r`, others as `\u00xx`); keep everything else
     *  — including non-ASCII — literal. */
    private fun writeJsonString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
        }
        sb.append('"')
    }

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

        /**
         * Throw a parse error worded and positioned like Python's `json` module
         * (`<msg>: line L column C (char P)`), so diagnostics/reports are identical across the
         * standalone CLI, the plugin and the original Python tool.
         */
        fun fail(msg: String, at: Int): Nothing {
            val head = s.substring(0, at.coerceIn(0, s.length))
            val line = head.count { it == '\n' } + 1
            val col = at - head.lastIndexOf('\n')   // lastIndexOf == -1 on line 1 → col = at + 1
            throw JsonException("$msg: line $line column $col (char $at)")
        }

        fun readValue(): Any? {
            skipWs()
            if (atEnd()) fail("Expecting value", pos)
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                else -> if (c == '-' || c.isDigit()) readNumber() else fail("Expecting value", pos)
            }
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (peek() != '"') fail("Expecting property name enclosed in double quotes", pos)
                val key = readString()
                skipWs()
                if (peek() != ':') fail("Expecting ':' delimiter", pos)
                pos++
                map[key] = readValue()
                skipWs()
                when (peek()) {
                    ',' -> { pos++; continue }
                    '}' -> { pos++; return map }
                    else -> fail("Expecting ',' delimiter", pos)
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
                    else -> fail("Expecting ',' delimiter", pos)
                }
            }
        }

        private fun readString(): String {
            val sb = StringBuilder()
            val start = pos
            pos++ // opening quote
            while (pos < s.length) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) fail("Unterminated string starting at", start)
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 > s.length) fail("Invalid \\uXXXX escape", pos - 2)
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4
                            }
                            else -> fail("Invalid \\escape", pos - 2)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            fail("Unterminated string starting at", start)
        }

        private fun readNumber(): Double {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: fail("Expecting value", start)
        }

        private fun readBoolean(): Boolean = when {
            s.startsWith("true", pos) -> { pos += 4; true }
            s.startsWith("false", pos) -> { pos += 5; false }
            else -> fail("Expecting value", pos)
        }

        private fun readNull(): Any? {
            if (s.startsWith("null", pos)) { pos += 4; return null }
            fail("Expecting value", pos)
        }

        private fun peek(): Char? = if (pos < s.length) s[pos] else null
    }
}
