package com.flowable.atlas.graph

import com.flowable.atlas.model.ModelElementDeclaration

/**
 * Where in its file a finding is: the `line` every surface opens — the explorer's open button, the IDE's
 * Findings panel, the overview's `file:line`.
 *
 * The checks know the element a finding is about, not where the element sits in the text: the parsers
 * read a DOM, which keeps no positions. So the files that carry such findings are read once more, here,
 * and each finding gets the line of its element's declaration (`id="gw"`), of the reference a missing
 * model is named by (`calledElement="courierProcess"`), of the expression a single model holds, or of the
 * script line a script problem sits on. Only findings that lack a line are touched, each file is read
 * once, and a file without a line break — a minified JSON model — is left alone: "line 1" says nothing
 * there. Nothing is added per element, so `graph.json` grows by one number per finding.
 */
internal object FindingLines {

    /**
     * Which script of a model a script finding is about: the body of the element [elementId] (a BPMN
     * `<script>`, a CMMN `<flowable:field name="script">`), or — with [listenerTag] — the script of the
     * [listenerIndex]-th listener of that tag on it.
     */
    class ScriptSite(val elementId: String, val listenerTag: String? = null, val listenerIndex: Int = 0)

    fun locate(
        findings: List<MutableMap<String, Any?>>,
        scripts: Map<MutableMap<String, Any?>, ScriptSite>,
        keyOf: (String) -> String?,
        read: (String) -> String?,
    ) {
        val texts = HashMap<String, String?>()
        fun text(file: String): String? = texts.getOrPut(file) { read(file)?.takeIf { '\n' in it } }
        for (f in findings) {
            if (f["line"] != null) continue
            val file = f["file"] as? String ?: continue
            val script = scripts[f]
            if (script != null) {
                // a script problem's own line counts from the start of the script body
                val n = (f["scriptLine"] as? Number)?.toInt() ?: continue
                val body = text(file)?.let { bodyLine(it, script) } ?: continue
                f["line"] = body + n - 1
                f.remove("scriptLine")
                continue
            }
            val t = text(file) ?: continue
            val off = when {
                f["element"] != null -> ModelElementDeclaration.offsetOf(t, f["element"].toString())
                f["check"] == "missingRefs" -> (f["node"] as? String)?.let(keyOf)?.let { referenceOffset(t, it) }
                f["check"] == "invalidExpr" || f["check"] == "suspectExpr" -> f["label"]?.toString()?.let { expressionOffset(t, it) }
                else -> null
            } ?: continue
            f["line"] = lineAt(t, off)
        }
    }

    /** 1-based line of [offset] in [text]. */
    fun lineAt(text: String, offset: Int): Int {
        var line = 1
        for (i in 0 until minOf(offset, text.length)) if (text[i] == '\n') line++
        return line
    }

    /** A missing model's key where the referring model names it: a quoted attribute or JSON value. */
    private fun referenceOffset(text: String, key: String): Int? {
        val q = Regex.escape(key)
        return Regex("""(["'])($q)\1""").find(text)?.groups?.get(2)?.range?.first
            ?: Regex(""">\s*($q)\s*<""").find(text)?.groups?.get(1)?.range?.first
    }

    /** An expression as the file spells it — as is, or XML-escaped inside an element or attribute. */
    private fun expressionOffset(text: String, expr: String): Int? {
        text.indexOf(expr).takeIf { it >= 0 }?.let { return it }
        val escaped = expr.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        text.indexOf(escaped).takeIf { it >= 0 }?.let { return it }
        return text.indexOf(escaped.replace("\"", "&quot;").replace("'", "&apos;")).takeIf { it >= 0 }
    }

    private fun idAttr(id: String) = Regex("""(?<![\w:-])id\s*=\s*(["'])${Regex.escape(id)}\1""")
    private val EXT_OPEN = Regex("""<(?:[\w.-]+:)?extensionElements\b""")
    private val EXT_CLOSE = Regex("""</(?:[\w.-]+:)?extensionElements\s*>""")
    private val SCRIPT_OPEN = Regex("""<(?:[\w.-]+:)?script\b[^>]*>""")
    private val SCRIPT_FIELD = Regex("""<(?:[\w.-]+:)?field\b[^>]*\bname\s*=\s*(["'])script\1[^>]*>""")
    private val FIELD_VALUE = Regex("""<(?:[\w.-]+:)?(?:string|expression)\b[^>]*>""")
    private val CDATA = "<![CDATA["

    /**
     * The 1-based file line where the script [site] names starts: past the opening tag, any whitespace
     * and a CDATA opener — where the trimmed body the validator counted from begins. DOM decoding adds no
     * line breaks, so script line N is this line + N − 1. Null when the text does not have the shape.
     */
    fun bodyLine(text: String, site: ScriptSite): Int? {
        val at = idAttr(site.elementId).find(text)?.range?.last ?: return null
        val end = bodyEnd(text, at) ?: return null
        val open: MatchResult = if (site.listenerTag != null) {
            // the element's own extension block, then its n-th listener of the tag, then that listener's script
            val ext = EXT_OPEN.find(text, at)?.takeIf { it.range.first < end } ?: return null
            val extEnd = EXT_CLOSE.find(text, ext.range.last)?.range?.first ?: return null
            val tag = Regex("""<(?:[\w.-]+:)?${Regex.escape(site.listenerTag)}\b""")
            val listener = tag.findAll(text.substring(0, extEnd), ext.range.last).elementAtOrNull(site.listenerIndex) ?: return null
            val listenerEnd = bodyEnd(text, listener.range.last) ?: return null
            SCRIPT_OPEN.find(text, listener.range.last)?.takeIf { it.range.first < listenerEnd } ?: return null
        } else {
            // a CMMN script task keeps its body in a field; a BPMN one in <script>, after any listeners
            val field = SCRIPT_FIELD.find(text, at)?.takeIf { it.range.first < end }
            val script = firstOutsideExtensions(text, at)?.takeIf { it.range.first < end }
            when {
                field != null && (script == null || field.range.first < script.range.first) -> {
                    val fieldEnd = bodyEnd(text, field.range.first + 1) ?: return null
                    FIELD_VALUE.find(text, field.range.last)?.takeIf { it.range.first < fieldEnd } ?: return null
                }
                else -> script ?: return null
            }
        }
        var i = open.range.last + 1
        while (i < text.length && text[i].isWhitespace()) i++
        if (text.startsWith(CDATA, i)) {
            i += CDATA.length
            while (i < text.length && text[i].isWhitespace()) i++
        }
        return lineAt(text, i)
    }

    /**
     * Where the element whose start tag holds [inTag] ends: the offset of its closing tag, found by
     * counting same-named start and end tags; null when the start tag closes itself (no body at all).
     */
    private fun bodyEnd(text: String, inTag: Int): Int? {
        val start = text.lastIndexOf('<', inTag).takeIf { it >= 0 } ?: return null
        val name = Regex("""<([\w.:-]+)""").matchAt(text, start)?.groupValues?.get(1) ?: return null
        val gt = text.indexOf('>', inTag).takeIf { it >= 0 } ?: return null
        if (text[gt - 1] == '/') return null
        val re = Regex("""<(/?)${Regex.escape(name)}(?=[\s/>])[^>]*?(/?)>""")
        var depth = 1
        for (m in re.findAll(text, gt + 1)) {
            when {
                m.groupValues[1] == "/" -> if (--depth == 0) return m.range.first
                m.groupValues[2] != "/" -> depth++
            }
        }
        return null
    }

    /** The first `<script>` after [from] that is not inside an `extensionElements` block — a listener's. */
    private fun firstOutsideExtensions(text: String, from: Int): MatchResult? {
        for (m in SCRIPT_OPEN.findAll(text, from)) {
            val before = text.substring(from, m.range.first)
            if (EXT_OPEN.findAll(before).count() <= EXT_CLOSE.findAll(before).count()) return m
        }
        return null
    }
}
