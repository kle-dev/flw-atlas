package com.flowable.atlas.parsing

/**
 * The JSON path of the value that holds a given offset in a JSON text — `rows[2].cols[0].label`.
 *
 * A Design model is one minified line, so "line 1, column 5087" names a marker only until the next
 * export shifts every column. The path of the element carrying it is what stays the same, and what a
 * reader can find in the editor. Structural only — strings are skipped whole, escapes honoured — and
 * tolerant: an unbalanced text yields whatever path the walk had reached.
 */
internal object JsonPath {

    private class Frame(val array: Boolean) {
        var key: String? = null
        var index = 0
        var expectKey = !array
    }

    fun at(text: String, offset: Int): String? {
        val stack = ArrayList<Frame>()
        var i = 0
        val end = minOf(offset, text.length)
        while (i < end) {
            when (val c = text[i]) {
                '"' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != '"') { if (text[j] == '\\') j++; j++ }
                    if (j >= end) break                       // the offset is inside this string
                    val top = stack.lastOrNull()
                    if (top != null && !top.array && top.expectKey) { top.key = text.substring(i + 1, j); top.expectKey = false }
                    i = j + 1
                    continue
                }
                '{' -> stack.add(Frame(array = false))
                '[' -> stack.add(Frame(array = true))
                '}', ']' -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                ',' -> stack.lastOrNull()?.let { if (it.array) it.index++ else it.expectKey = true }
                else -> {}
            }
            i++
        }
        if (stack.isEmpty()) return null
        val sb = StringBuilder()
        for (f in stack) {
            if (f.array) sb.append('[').append(f.index).append(']')
            else if (f.key != null) { if (sb.isNotEmpty()) sb.append('.'); sb.append(f.key) }
        }
        return sb.toString().ifEmpty { null }
    }
}
