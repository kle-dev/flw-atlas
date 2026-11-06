package com.flowable.atlas.render

import com.flowable.atlas.graph.CheckCatalog

/**
 * Shared formatting helpers for the Markdown artifacts — the ones an LLM reads.
 *
 * The renderers are ports of a Python original, so container and absent values used to reach the page
 * through Python `str()`/`repr()` semantics: a list printed as `['total']`, a map as
 * `{'kind': 'rest', 'url': '/api/customers'}`, an unset field as `None` (`base=None auth=None`). All
 * three are noise a reader has to decode around, and they cost tokens for no information. Every list,
 * map and optional value on the page goes through here instead: lists become `a, b`, maps become the
 * one line they actually mean, and an absent value makes its label disappear rather than print `None`.
 *
 * [cap] is the token-budget primitive the summary was already built on (it lived there privately);
 * it is shared now so every artifact elides long lists the same recognisable way.
 */
object Fmt {

    /**
     * The basename the CLI/plugin give this project's artifacts (`<name>.summary.md`, …), so a
     * generated file can point a reader at its siblings by their real names instead of at CLI flags —
     * an agent reading `<name>.summary.md` inside a repo has the files, not the command line.
     * Mirrors `os.path.splitext(os.path.basename(...))[0] or "project"`.
     */
    /**
     * `610 models (3 files · 27 archives)` — how much of a project Atlas read. `stats.models` counts
     * *files* and used to be printed as "3 models" for a repository whose 27 Design exports held 610.
     */
    /**
     * The health headline every text surface leads with: `3 defects · 41 advice`. Reads the two counts
     * `Findings.apply` writes into `stats`, and says nothing for a class that is at zero — "0 defects" is
     * worth a line only when it is the whole story, and the caller decides that.
     */
    fun healthHeadline(st: Map<*, *>?): String {
        val d = (st?.get("defects") as? Number)?.toInt() ?: 0
        val a = (st?.get("advice") as? Number)?.toInt() ?: 0
        val parts = ArrayList<String>()
        if (d > 0) parts.add("$d defect" + if (d == 1) "" else "s")
        if (a > 0) parts.add("$a advice")
        return parts.joinToString(" · ")
    }

    /** The per-check count lines of a health block, defects and advice on separate lines. */
    fun healthLines(checks: Map<String, Any?>): List<String> {
        val perCheck = checks.entries.filter { it.key != "open" && it.key != "waived" }
        return listOf(CheckCatalog.DEFECT, CheckCatalog.ADVICE).mapNotNull { kind ->
            val own = perCheck.filter { CheckCatalog.kind(it.key) == kind }
            if (own.isEmpty()) null
            else (if (kind == CheckCatalog.DEFECT) "Defects — " else "Advice — ") +
                own.joinToString(" · ") { "${CheckCatalog.label(it.key)}: ${it.value}" }
        }
    }

    fun modelScale(st: Map<*, *>): String {
        val n = (st["modelCount"] as? Number)?.toInt()
        val files = (st["models"] as? Number)?.toInt() ?: 0
        val archives = (st["archives"] as? Number)?.toInt() ?: 0
        if (n == null) return "$files model files"
        val where = if (archives > 0) "$files files · $archives archives" else "$files files"
        return "$n models ($where)"
    }

    /** [root] as the absolute path it names: `.`, `sub/..` and a trailing slash all resolve to the directory. */
    private fun canonicalRoot(root: java.io.File): java.io.File =
        java.io.File(root.path.trimEnd('/').ifEmpty { "." }).absoluteFile.normalize()

    /**
     * The project's name as every artifact shows it — the summary's and the overview's heading, the
     * explorer's and the ER page's title. Normalized, because `File(".").absoluteFile.name` is `.`: a
     * project analysed from inside itself was headed `.` and, worse, every such project shared the pages'
     * browser storage. Unlike [artifactName] it keeps an archive's extension (`app.zip`): it names what was
     * read, not a file to write.
     */
    fun projectName(root: java.io.File): String = canonicalRoot(root).name.ifEmpty { "project" }

    /**
     * What the generated pages key their browser storage on — accepted findings, open tabs, recents, ER
     * drafts. Every page opened from `file://` shares one localStorage, and two projects can share a name
     * (`app`, or every checkout of one repository), so the name alone let one project's unsaved decisions
     * show up — and get exported — in another's. A hash of the absolute path, not the path itself: a page is
     * mailed to reviewers, and the author's directory layout is not theirs to read.
     */
    fun projectKey(root: java.io.File): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonicalRoot(root).path.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }

    /**
     * The `<name>` of every `<name>.*` artifact: [projectName] with its last extension cut, as Python's
     * `os.path.splitext` did — a leading-dot name (`.DEMO-solution`) has no extension. The CLI, the plugin
     * and every artifact that names a sibling file call this one function, so a file never points at a
     * sibling written under another name.
     */
    fun artifactName(root: java.io.File): String {
        val base = projectName(root)
        val dot = base.lastIndexOf('.')
        val stem = if (dot < 0) base else {
            var allDots = true
            for (i in 0 until dot) if (base[i] != '.') { allDots = false; break }
            if (allDots) base else base.substring(0, dot)
        }
        return stem.ifEmpty { "project" }
    }

    /** Every graph node's label by id — for naming the models an expression finding sits in. */
    fun nodeLabels(result: Map<String, Any?>): Map<String, String> {
        val nodes = ((result["graph"] as? Map<*, *>)?.get("nodes") as? List<*>).orEmpty()
        return nodes.mapNotNull { it as? Map<*, *> }.mapNotNull { n ->
            val id = n["id"] as? String ?: return@mapNotNull null
            id to ((n["label"] ?: n["key"])?.toString() ?: id.substringAfter(':'))
        }.toMap()
    }

    /**
     * `` in `Order Process`, `Fulfilment` (+2 more) `` — where an expression or binding finding is: the
     * models holding the expression, which is no file of its own. `""` for any other finding, so a
     * reader is never sent to grep for the expression text.
     */
    fun findingModels(f: Map<String, Any?>, labels: Map<String, String>, n: Int = 3): String {
        val ms = (f["models"] as? List<*>)?.map { it.toString() }?.takeIf { it.isNotEmpty() } ?: return ""
        return "in " + ms.take(n).joinToString(", ") { "`${labels[it] ?: it.substringAfter(':')}`" } +
            if (ms.size > n) " (+${ms.size - n} more)" else ""
    }

    /** `a, b, c … (+7 more)` — the artifacts' one and only list-truncation shape. */
    fun cap(items: List<String>, n: Int = 15): String {
        val extra = if (items.size > n) " … (+${items.size - n} more)" else ""
        return items.take(n).joinToString(", ") + extra
    }

    /** A JSON-ish list as `a, b, c` (elements stringified, blanks dropped); `null`/empty → `""`. */
    fun list(v: Any?, sep: String = ", "): String = when (v) {
        null -> ""
        is Collection<*> -> v.mapNotNull { scalar(it) }.filter { it.isNotEmpty() }.joinToString(sep)
        else -> scalar(v) ?: ""
    }

    /** Same as [list] but each element in backticks — for identifier-ish lists (columns, payload keys). */
    fun codeList(v: Any?, sep: String = ", "): String = when (v) {
        null -> ""
        is Collection<*> -> v.mapNotNull { scalar(it) }.filter { it.isNotEmpty() }.joinToString(sep) { "`$it`" }
        else -> scalar(v)?.takeIf { it.isNotEmpty() }?.let { "`$it`" } ?: ""
    }

    /** `" label=value"` when there is a value, `""` when there is not — never `label=None`. */
    fun opt(label: String, v: Any?, code: Boolean = false): String {
        val s = scalar(v)?.takeIf { it.isNotEmpty() } ?: return ""
        return if (code) " $label=`$s`" else " $label=$s"
    }

    /** Join the non-empty parts of a line, so an omitted [opt] never leaves a double space. */
    fun join(vararg parts: String): String =
        parts.filter { it.isNotBlank() }.joinToString(" ") { it.trim() }

    /**
     * `label: value · label: value` — pairs whose value is blank vanish entirely.
     *
     * Used where a line carries several small attributes, at least one of which may itself be a list:
     * `payload=a, b correlation=c` leaves a reader guessing where `payload` ends, whereas
     * `payload: a, b · correlation: c` does not.
     */
    fun fields(vararg pairs: Pair<String, String>): String =
        pairs.filter { it.second.isNotBlank() }.joinToString(" · ") { "${it.first}: ${it.second}" }

    /**
     * A form/page data source (`{kind, key, op, url}`) as the one line it means:
     * `rest \`/api/customers\``, `service \`customerService.findAll\``, `dataObject \`customerDO.findAll\``.
     * Unknown shapes fall back to their entries so nothing is silently dropped.
     */
    fun dataSource(v: Any?): String {
        val m = v as? Map<*, *> ?: return scalar(v) ?: ""
        val kind = scalar(m["kind"])?.takeIf { it.isNotEmpty() } ?: "source"
        val url = scalar(m["url"])?.takeIf { it.isNotEmpty() }
        val key = scalar(m["key"])?.takeIf { it.isNotEmpty() }
        val op = scalar(m["op"])?.takeIf { it.isNotEmpty() }
        val what = when {
            url != null -> url
            key != null && op != null -> "$key.$op"
            key != null -> key
            else -> m.entries.filter { it.key != "kind" }
                .mapNotNull { e -> scalar(e.value)?.takeIf { it.isNotEmpty() }?.let { "${e.key}=$it" } }
                .joinToString(", ")
        }
        return if (what.isEmpty()) kind else "$kind `$what`"
    }

    /**
     * A scalar as text; `null` stays `null` so callers can omit the whole label. Numbers print the way
     * the rest of the report prints them (integral values without a trailing `.0`), because the extract
     * tree carries JSON numbers as [Double].
     */
    fun scalar(v: Any?): String? = when (v) {
        null -> null
        is Boolean -> if (v) "yes" else "no"
        is Double -> if (v.isFinite() && v == Math.floor(v)) v.toLong().toString() else v.toString()
        is Float -> scalar(v.toDouble())
        is Collection<*> -> list(v)
        is Map<*, *> -> dataSource(v)
        else -> v.toString()
    }
}
