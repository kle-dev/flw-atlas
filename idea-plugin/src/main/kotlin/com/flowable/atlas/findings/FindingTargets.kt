package com.flowable.atlas.findings

import com.flowable.atlas.navigation.ModelElements

/**
 * Where *Atlas Findings* takes a reader who opens a finding: which file, and where in it.
 *
 * A finding names its file and line when the analysis knows them, and most do. The rest are the reason this
 * exists. A variable, a service operation, a custom function, an expression held by several models — none
 * is a file of its own, and opening such a row did nothing at all. A finding in a minified JSON model has
 * no line ("line 1" says nothing there), and one about a column or a config path names the file and not
 * where in it; those opened at the top. The analysis already says which models hold such a node and what
 * the finding is about, so this reads it from there rather than growing `graph.json`: the [index] is kept
 * in memory beside the findings, for the window alone.
 */
internal object FindingTargets {

    /** Below this a search string matches all over a model's text: `id`, `to`. */
    private const val MIN_NEEDLE = 3

    /**
     * For every finding that has no file of its own, the files of the models it lives in, keyed by the
     * finding's node: the models that use it (`usedBy` — an expression, a variable), the models whose edges
     * point at it, the service model that declares an operation, the code that registers a custom function.
     * App membership (`contains`) is not use. A node with no such file is left out.
     */
    @Suppress("UNCHECKED_CAST")
    fun index(result: Map<String, Any?>): Map<String, List<String>> {
        val findings = result["findings"] as? List<Map<String, Any?>> ?: return emptyMap()
        val wanted = findings.filter { it["file"] == null }.mapNotNullTo(HashSet()) { it["node"] as? String }
        if (wanted.isEmpty()) return emptyMap()
        val graph = result["graph"] as? Map<String, Any?> ?: return emptyMap()
        val nodes = (graph["nodes"] as? List<Map<String, Any?>>).orEmpty().associateBy { it["id"] as? String }
        // node → the files it lives in, in the order they were found: its own users first, then referrers
        val files = HashMap<String, LinkedHashSet<String>>()
        fun add(node: String, file: Any?) {
            (file as? String)?.takeIf { it.isNotBlank() }?.let { files.getOrPut(node) { LinkedHashSet() }.add(it) }
        }
        for (id in wanted) {
            val node = nodes[id] ?: continue
            val data = node["data"] as? Map<String, Any?> ?: continue
            for (user in (data["usedBy"] as? List<*>).orEmpty()) add(id, nodes[user.toString()]?.get("file"))
            // an operation is declared in its service, which no edge says: an unused one has no users at all
            if (node["type"] == "serviceOperation") data["service"]?.let { add(id, nodes["service:$it"]?.get("file")) }
            // a custom function is registered in code, by file label
            if (node["type"] == "customFunction") {
                add(id, data["codeFile"])
                for (source in (data["sources"] as? List<*>).orEmpty()) add(id, source)
            }
        }
        for (e in (graph["edges"] as? List<Map<String, Any?>>).orEmpty()) {
            if (e["rel"] == "contains") continue
            val t = e["t"] as? String ?: continue
            if (t !in wanted) continue
            add(t, nodes[e["s"] as? String]?.get("file"))
        }
        return files.mapValues { it.value.toList() }
    }

    /** The files [finding] opens in: its own, else the models [targets] lists for its node. */
    fun filesOf(finding: Map<String, Any?>, targets: Map<String, List<String>>): List<String> {
        (finding["file"] as? String)?.let { return listOf(it) }
        return (finding["node"] as? String)?.let { targets[it] }.orEmpty()
    }

    /**
     * What to look for in the file, most telling first. An expression finding is about the expression
     * itself; any other names what it is about in its `subject` — a column (`ord_order.score_`), a config
     * path, a scope — whose last step is what the file spells. Then the snippet, the label (a variable's
     * name, an operation's key) and the node's key.
     */
    fun needles(finding: Map<String, Any?>): List<String> {
        val check = finding["check"]?.toString()
        val label = finding["label"]?.toString()
        // `@20` is an offset into an expression, not text
        val subject = finding["subject"]?.toString()?.takeUnless { it.startsWith("@") }
        val all = buildList {
            if (check == "invalidExpr" || check == "suspectExpr") add(label)
            add(subject)
            add(subject?.let(::lastStep))
            add(finding["snippet"]?.toString())
            add(label)
            add(label?.let(::lastStep))
            add((finding["node"] as? String)?.substringAfter(':'))
        }
        return all.filterNotNull().map { it.trim() }.filter { it.length >= MIN_NEEDLE }.distinct()
    }

    /**
     * Where in [text] — a model file's — [finding] is: its element's declaration when it names one, else
     * the first of its [needles] the text spells. Null when none is there; the file then opens at the top.
     */
    fun offsetIn(text: String, finding: Map<String, Any?>): Int? {
        (finding["element"] as? String)?.let { el -> ModelElements.declarationOffset(text, el)?.let { return it } }
        for (needle in needles(finding)) find(text, needle)?.let { return it }
        return null
    }

    /**
     * [needle] in [text]: quoted first — a declaration or a value, `"key": "archive"`, `target="amount"` —
     * before a mention in a URL or a sentence; then as it stands; then as XML and JSON spell it, inside an
     * attribute or the escaped `editorJson` string of a Design export.
     */
    private fun find(text: String, needle: String): Int? {
        for (q in QUOTES) {
            val at = text.indexOf(q + needle + q)
            if (at >= 0) return at + q.length
        }
        // element text escapes `&<>`, an attribute its quotes too
        val xmlText = needle.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val xmlAttr = xmlText.replace("\"", "&quot;").replace("'", "&apos;")
        val json = needle.replace("\\", "\\\\").replace("\"", "\\\"")
        for (form in listOf(needle, xmlText, xmlAttr, json).distinct()) {
            val at = text.indexOf(form)
            if (at >= 0) return at
        }
        return null
    }

    private val QUOTES = listOf("\"", "'", "\\\"")

    private fun lastStep(s: String): String = s.substring(s.indexOfLast { it == '.' || it == ':' || it == '/' || it == '#' } + 1)
}
