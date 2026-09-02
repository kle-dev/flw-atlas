package com.flowable.atlas.render

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
internal object Fmt {

    /**
     * The basename the CLI/plugin give this project's artifacts (`<name>.summary.md`, …), so a
     * generated file can point a reader at its siblings by their real names instead of at CLI flags —
     * an agent reading `<name>.summary.md` inside a repo has the files, not the command line.
     * Mirrors `os.path.splitext(os.path.basename(...))[0] or "project"`.
     */
    fun artifactName(root: java.io.File): String {
        val base = java.io.File(root.path.trimEnd('/')).absoluteFile.name
        val dot = base.lastIndexOf('.')
        val stem = if (dot < 0) base else {
            var allDots = true
            for (i in 0 until dot) if (base[i] != '.') { allDots = false; break }
            if (allDots) base else base.substring(0, dot)
        }
        return stem.ifEmpty { "project" }
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
