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

    /** Python's `json` gives up at its recursion limit (about a thousand levels); no model comes near it. */
    private const val MAX_DEPTH = 1000

    // A UTF-8 byte-order mark is not whitespace to `isWhitespace()`, so a BOM'd model — an export edited
    // on Windows, say — parsed as "Expecting value at char 0" and vanished from the report.
    private fun P(text: String, dropBom: Boolean) = P(if (dropBom) text.removePrefix("\uFEFF") else text)

    fun parse(text: String): Any? {
        val p = P(text, dropBom = true)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.atEnd()) p.fail("Extra data", p.pos)
        return v
    }

    fun parseOrNull(text: String): Any? =
        try {
            val p = P(text, dropBom = true)
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

    /**
     * Re-indent JSON **without reading its values** — one token per line, [indent] spaces per level,
     * `": "` between a key and its value, empty objects and arrays left inline.
     *
     * Token-level on purpose. `stringify(parseOrNull(text), indent)` is the one-line alternative, but it
     * round-trips every value through [Double] (see [writeIndented]), so `"version": 1` comes back as
     * `1.0` and a long integer in scientific notation. Here every string, number and keyword is copied
     * through character for character and only the whitespace *between* them changes — which is what a
     * side-by-side comparison needs: both sides laid out the same way, neither showing a value its file
     * does not contain.
     *
     * Null when [text] is not a `{`/`[`-rooted, correctly paired document (an unterminated string, a
     * missing bracket): the caller then shows the bytes as they are rather than a guess at them.
     */
    fun reindent(text: String, indent: Int = 2): String? {
        val s = text.removePrefix("\uFEFF")
        var i = 0
        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length || (s[i] != '{' && s[i] != '[')) return null

        val sb = StringBuilder(s.length + s.length / 3)
        val open = ArrayDeque<Char>()          // the closers still expected, innermost last
        var rootClosed = false                 // anything but whitespace after this is trailing junk
        fun newline() {
            sb.append('\n')
            repeat(open.size * indent) { sb.append(' ') }
        }
        while (i < s.length) {
            when (val c = s[i]) {
                '"' -> {
                    val end = copyString(s, i, sb) ?: return null
                    i = end
                }
                '{', '[' -> {
                    val closer = if (c == '{') '}' else ']'
                    var next = i + 1
                    while (next < s.length && s[next].isWhitespace()) next++
                    if (next < s.length && s[next] == closer) {
                        sb.append(c).append(closer)         // {} / [] stay on one line
                        i = next + 1
                        rootClosed = open.isEmpty()
                    } else {
                        sb.append(c)
                        open.addLast(closer)
                        newline()
                        i++
                    }
                }
                '}', ']' -> {
                    if (open.removeLastOrNull() != c) return null
                    newline()
                    sb.append(c)
                    i++
                    rootClosed = open.isEmpty()
                }
                ',' -> { sb.append(c); newline(); i++ }
                ':' -> { sb.append(": "); i++ }
                else -> {
                    if (!c.isWhitespace()) sb.append(c)     // a number/keyword character; whitespace goes
                    i++
                }
            }
            if (rootClosed) {
                while (i < s.length && s[i].isWhitespace()) i++
                return if (i >= s.length) sb.toString() else null
            }
        }
        return null                            // ran out of input with brackets still open
    }

    /** Copies the string literal starting at the quote at [from] verbatim; returns the index after its
     *  closing quote, or null when it is never closed. */
    private fun copyString(s: String, from: Int, sb: StringBuilder): Int? {
        sb.append('"')
        var i = from + 1
        while (i < s.length) {
            val c = s[i]
            sb.append(c)
            when (c) {
                '\\' -> {
                    if (i + 1 >= s.length) return null
                    sb.append(s[i + 1])
                    i += 2
                }
                '"' -> return i + 1
                else -> i++
            }
        }
        return null
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

    // The compact writer escapes exactly what the indented one does: a raw control character (a
    // vertical tab pasted from Word into a description) is invalid inside a JSON string, and the
    // explorer's JSON.parse refused the whole page over it.
    private fun writeString(s: String, sb: StringBuilder) = writeJsonString(s, sb)

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

        /** Open containers. The parser recurses per level, and a StackOverflowError is an Error that gets
         *  past every `catch (e: Exception)` above it — so nesting past Python's own limit fails as JSON. */
        private var depth = 0

        private fun enter() { if (++depth > MAX_DEPTH) fail("Nesting deeper than $MAX_DEPTH levels", pos) }

        private fun readObject(): Map<String, Any?> {
            enter()
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (peek() == '}') { pos++; depth--; return map }
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
                    '}' -> { pos++; depth--; return map }
                    else -> fail("Expecting ',' delimiter", pos)
                }
            }
        }

        private fun readArray(): List<Any?> {
            enter()
            val list = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (peek() == ']') { pos++; depth--; return list }
            while (true) {
                list += readValue()
                skipWs()
                when (peek()) {
                    ',' -> { pos++; continue }
                    ']' -> { pos++; depth--; return list }
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
                                // Four hex digits, checked here: `toInt(16)` threw a NumberFormatException the
                                // callers (the playground's "Invalid payload JSON") do not catch, and took "+1f".
                                if (pos + 4 > s.length || (pos until pos + 4).any { Character.digit(s[it], 16) < 0 })
                                    fail("Invalid \\uXXXX escape", pos - 2)
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
