package com.flowable.atlas.parsing

/**
 * Splits the raw text of a file that holds several models — a deployment `.bpmn20.xml` with two
 * processes, a CMMN file with two cases, a DMN file with several decisions — into one part per model
 * plus the remainder (the definitions header, messages, signals, diagram interchange).
 *
 * The regex harvests ([VarHarvest], the `${…}` / `{{…}}` walk in the extractor) work on raw text and
 * used to credit the whole file to every model in it, so a variable written in process A and read in
 * process B looked like a read *and* a write in both — `usedBy` inflated, and the unused-variable
 * check judged the wrong scope. Each model gets the text inside its own element now; what sits outside
 * every element still belongs to all of them, because nothing in the file says otherwise.
 */
internal object ModelSpans {

    private val ELEMENT_FOR_TYPE = mapOf("bpmn" to "process", "cmmn" to "case", "dmn" to "decision")

    /**
     * `(keys, text)` parts: one per model key with the text of its element, then one with every key
     * and the text outside all elements. A file with one model, a JSON model, or an element Atlas cannot
     * locate for one of the keys comes back as a single part `(mkeys, raw)` — the old behaviour, never
     * a partial attribution.
     */
    fun split(raw: String, mtype: String, mkeys: List<Any?>): List<Pair<List<Any?>, String>> {
        val whole = listOf(mkeys to raw)
        val keys = mkeys.filterNotNull()
        val element = ELEMENT_FOR_TYPE[mtype] ?: return whole
        if (keys.size < 2) return whole

        val spans = ArrayList<Pair<Any?, IntRange>>()
        for (k in keys) {
            val open = Regex("<(?:\\w+:)?$element\\b[^>]*\\bid=\"${Regex.escape(k.toString())}\"[^>]*>")
                .find(raw) ?: return whole
            val range = if (open.value.endsWith("/>")) {
                open.range
            } else {
                val close = Regex("</(?:\\w+:)?$element\\s*>").find(raw, open.range.last) ?: return whole
                open.range.first..close.range.last
            }
            spans.add(k to range)
        }
        spans.sortBy { it.second.first }

        val parts = ArrayList<Pair<List<Any?>, String>>()
        val rest = StringBuilder()
        var cursor = 0
        for ((k, r) in spans) {
            if (r.first < cursor) return whole            // overlapping elements: not a shape we can split
            rest.append(raw, cursor, r.first)
            parts.add(listOf(k) to raw.substring(r))
            cursor = r.last + 1
        }
        rest.append(raw, cursor, raw.length)
        parts.add(mkeys to rest.toString())
        return parts
    }
}
