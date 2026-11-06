package com.flowable.atlas.diagram

/**
 * Text and numbers as the three SVG painters ([DiagramSvgRenderer], [FormSvgRenderer],
 * [DmnTableSvgRenderer]) write them — one copy, where each painter used to keep its own.
 *
 * The `.svg` is a standalone XML document, and XML 1.0 forbids most control characters even when escaped.
 * A label with one of them — a vertical tab pasted from Word into a form field's label, the input
 * [com.flowable.atlas.model.MiniJson] already meets in descriptions — made the whole drawing unreadable:
 * JSVG, which the IDE preview and gutter picture use, rejects the document and returns nothing, and a
 * browser shows a parse error instead of the picture. Only a JSON model (a form or page) can carry such a
 * character; an XML model holding one would not have parsed in the first place.
 */
internal object SvgText {

    /**
     * [s] escaped for an XML attribute or text node, with every UTF-16 unit that is not an XML 1.0 `Char`
     * written as a space: U+0000–U+0008, U+000B, U+000C, U+000E–U+001F, U+FFFE, U+FFFF and a surrogate
     * without its partner. A space rather than nothing, because a pasted vertical tab or form feed stood
     * for a line break, and dropping it would glue two words together. A valid surrogate pair (an emoji)
     * is kept.
     */
    fun esc(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\'' -> append("&#39;")
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                    append(c).append(s[i + 1])
                    i++
                }
                c.isSurrogate() -> append(' ')
                c < ' ' && c != '\t' && c != '\n' && c != '\r' -> append(' ')
                c == '\uFFFE' || c == '\uFFFF' -> append(' ')
                else -> append(c)
            }
            i++
        }
    }

    /** Locale-free number formatting: round to 2 decimals, drop a trailing `.0`. */
    fun fmt(v: Double): String {
        val r = Math.round(v * 100.0) / 100.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }
}
