package com.flowable.atlas.parsing

/**
 * The XML sibling of [JsonPath]: what holds a given offset in an XML text — the innermost element with
 * an `id` around it, and what directly carries the offset (`documentation`, `@name`, a comment).
 *
 * A marker with no text of its own (`<documentation>TODO</documentation>`) used to be told apart by its
 * line, so accepting it lapsed the moment a line was inserted above it, and the finding named no element.
 * The element and what carries the marker stay put. A structural walk only — comments, CDATA sections and
 * processing instructions are skipped whole, attribute values may hold `>` — and tolerant: a text it
 * cannot follow yields whatever the walk had reached.
 */
internal object XmlPath {

    /**
     * [ownerId] and [ownerTag] (local name) of the innermost element with a non-blank `id`; [holder] what
     * directly holds the offset: `@attr` inside a start tag, `#comment` inside a comment, else the local
     * name of the innermost open element; [value] the attribute value, comment or text around the offset,
     * whitespace collapsed, at most 160 characters.
     */
    data class Site(val ownerId: String, val ownerTag: String, val holder: String, val value: String?)

    private class Frame(val tag: String, val id: String?)

    private fun local(name: String) = name.substringAfterLast(':')
    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim().take(160).ifEmpty { null }

    fun at(text: String, offset: Int): Site? {
        val stack = ArrayList<Frame>()
        fun site(holder: String, value: String?, self: Frame? = null): Site? {
            val owner = (if (self?.id != null) self else null) ?: stack.lastOrNull { !it.id.isNullOrBlank() } ?: return null
            return Site(owner.id!!, owner.tag, holder, value)
        }
        var i = 0
        while (i < text.length && i <= offset) {
            if (text[i] != '<') {
                val next = text.indexOf('<', i).let { if (it < 0) text.length else it }
                if (offset < next) return site(stack.lastOrNull()?.tag ?: return null, clean(text.substring(i, next)))
                i = next
                continue
            }
            when {
                text.startsWith("<!--", i) -> {
                    val end = text.indexOf("-->", i + 4).let { if (it < 0) text.length else it }
                    if (offset < end + 3) return site("#comment", clean(text.substring(i + 4, end)))
                    i = end + 3
                }
                text.startsWith("<![CDATA[", i) -> {
                    val end = text.indexOf("]]>", i + 9).let { if (it < 0) text.length else it }
                    if (offset < end + 3) return site(stack.lastOrNull()?.tag ?: return null, clean(text.substring(i + 9, end)))
                    i = end + 3
                }
                text.startsWith("<?", i) || text.startsWith("<!", i) -> {
                    i = text.indexOf('>', i).let { if (it < 0) text.length else it + 1 }
                }
                text.startsWith("</", i) -> {
                    val end = text.indexOf('>', i).let { if (it < 0) text.length else it }
                    val tag = local(text.substring(i + 2, end).trim())
                    // pop to the matching element, tolerating one left open
                    val at = stack.indexOfLast { it.tag == tag }
                    if (at >= 0) while (stack.size > at) stack.removeAt(stack.lastIndex)
                    i = end + 1
                }
                else -> {
                    // a start tag: its name, then attributes whose quoted values may hold anything
                    var j = i + 1
                    while (j < text.length && !text[j].isWhitespace() && text[j] != '>' && text[j] != '/') j++
                    val tag = local(text.substring(i + 1, j))
                    var id: String? = null
                    var attrAt: Pair<String, String>? = null
                    var selfClosing = false
                    while (j < text.length && text[j] != '>') {
                        val c = text[j]
                        if (c == '/' ) { selfClosing = true; j++; continue }
                        if (c.isWhitespace()) { j++; continue }
                        val nameStart = j
                        while (j < text.length && text[j] != '=' && !text[j].isWhitespace() && text[j] != '>') j++
                        val name = local(text.substring(nameStart, j))
                        while (j < text.length && (text[j].isWhitespace() || text[j] == '=')) j++
                        if (j < text.length && (text[j] == '"' || text[j] == '\'')) {
                            val q = text[j]
                            val end = text.indexOf(q, j + 1).let { if (it < 0) text.length else it }
                            val value = text.substring(j + 1, end)
                            if (name == "id") id = value
                            if (offset in nameStart..end) attrAt = name to value
                            j = end + 1
                        }
                        selfClosing = false
                    }
                    val frame = Frame(tag, id?.takeIf { it.isNotBlank() })
                    if (offset <= j) {
                        // inside this start tag: an attribute of this element, which may be its own owner
                        val (name, value) = attrAt ?: (tag to "")
                        return site("@$name", clean(value), frame)
                    }
                    if (!selfClosing) stack.add(frame)
                    i = j + 1
                }
            }
        }
        return null
    }
}
