package com.flowable.atlas.model

/**
 * Where a model file declares its key — the offset a jump should land on.
 *
 * Ctrl+click on a model key used to resolve to the *file*: line 1 of a minified Design JSON, or the
 * top of a deployment XML holding three processes, with nothing saying where the key is. The
 * declaration is one attribute (`<process id="…">`) or one property (`"key": "…"`), and this finds it
 * in the text so both the reference and Search Everywhere can land on it.
 */
object ModelKeyDeclaration {

    private val XML_ELEMENT = mapOf(ModelType.PROCESS to "process", ModelType.CASE to "case", ModelType.DECISION to "decision")

    /** Offset of the first character of [key] where [type]'s file declares it, or null when not found. */
    fun offsetOf(text: String, type: ModelType, key: String): Int? {
        val k = Regex.escape(key)
        val re = XML_ELEMENT[type]?.let { el ->
            // the `id` attribute of the model's own element, wherever it sits among the other attributes
            Regex("<(?:\\w+:)?$el\\b[^>]*?\\bid\\s*=\\s*\"($k)\"")
        } ?: Regex("\"key\"\\s*:\\s*\"($k)\"")
        val m = re.find(text) ?: return null
        return m.groups[1]?.range?.first
    }

    /** `(line, column)`, both 0-based, of [offset] in [text] — what an editor descriptor wants. */
    fun lineColumn(text: String, offset: Int): Pair<Int, Int> {
        val o = offset.coerceIn(0, text.length)
        var line = 0
        var lineStart = 0
        for (i in 0 until o) if (text[i] == '\n') { line++; lineStart = i + 1 }
        return line to (o - lineStart)
    }
}
