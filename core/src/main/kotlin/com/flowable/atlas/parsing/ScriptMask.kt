package com.flowable.atlas.parsing

/**
 * Blanks the `${…}` / `#{…}` inside script bodies out of a model's raw text before the expression harvest.
 *
 * A Groovy script writes `"${user?.firstName}"` and a JavaScript one `` `${total}` `` — string
 * interpolation of the script's own language. The raw harvest read those as backend expressions and
 * validated them as JUEL (where `?.` is a syntax error), credited the script's helpers (`flw`,
 * `flwTimeUtils`) as beans the model calls, and turned every interpolated local into a "variable".
 * Scripts have a reader of their own — the parsers hand each body to
 * [com.flowable.atlas.script.ScriptValidator] and [VarHarvest.collectScriptVars] — so hiding those from
 * the text harvest loses nothing.
 *
 * A `{{…}}` inside a script body is left alone on purpose: the form engine evaluates a script button's
 * bindings before the script runs, so `"script": "{{amount * 1.081}}"` is a real binding.
 *
 * Every offset is kept: a masked character becomes a space and newlines stay, so spans and line
 * numbers computed on the masked text still point into the file.
 */
internal object ScriptMask {

    /** `"script": "` — an action's `scriptInfo.script`, a script operation's `config.script`, a form
     *  script button's `script`. Only the opening is a regex: the value is scanned by hand, because a
     *  `((?:[^"\\]|\\.)*)` over a 40 KB Groovy body recurses once per character in Java's regex engine
     *  and blew the stack on a real project. */
    private val JSON_SCRIPT_OPEN_RE = Regex("\"script\"\\s*:\\s*\"")

    /** `<script>…</script>` — a script task's body, a listener's script. */
    private val XML_SCRIPT_RE = Regex("<(?:\\w+:)?script\\b[^>]*>(.*?)</(?:\\w+:)?script>", RegexOption.DOT_MATCHES_ALL)

    /** CMMN's script task keeps its body in `<flowable:field name="script">`. */
    private val XML_SCRIPT_FIELD_RE =
        Regex("<(?:\\w+:)?field\\s+name=\"script\"\\s*>(.*?)</(?:\\w+:)?field>", RegexOption.DOT_MATCHES_ALL)

    /** The range of every JSON script string's contents — up to its closing unescaped quote. */
    private fun jsonScriptBodies(raw: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (m in JSON_SCRIPT_OPEN_RE.findAll(raw)) {
            val start = m.range.last + 1
            var i = start
            while (i < raw.length && raw[i] != '"') { if (raw[i] == '\\') i++; i++ }
            if (i > start) out.add(start until minOf(i, raw.length))
        }
        return out
    }

    fun mask(raw: String, xml: Boolean): String {
        val bodies = if (xml) {
            listOf(XML_SCRIPT_RE, XML_SCRIPT_FIELD_RE)
                .flatMap { re -> re.findAll(raw).mapNotNull { it.groups[1]?.range }.filter { !it.isEmpty() } }
        } else jsonScriptBodies(raw)
        if (bodies.isEmpty()) return raw
        var out: StringBuilder? = null
        for (e in Constants.EXPR_RE.findAll(raw)) {
            if (bodies.none { e.range.first in it }) continue
            val sb = out ?: StringBuilder(raw).also { out = it }
            for (i in e.range) if (sb[i] != '\n') sb.setCharAt(i, ' ')
        }
        return out?.toString() ?: raw
    }
}
