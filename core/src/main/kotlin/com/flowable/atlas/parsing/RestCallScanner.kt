package com.flowable.atlas.parsing

import kotlin.math.abs

/**
 * Locates the outbound REST calls a model file makes — the `requestUrl` value of an HTTP service task
 * (`flowable:type="http"`), together with its nearby `requestMethod` and the exact offset range of the
 * URL value in the text. Companion to [ModelUsageLocator] (which locates Java-symbol references): this
 * one locates the endpoint URLs so a Spring controller's `@GetMapping` can find the models that call it.
 *
 * Deliberately loose, pure text scanning (no XML/JSON parse, no I/O, no IntelliJ) — mirroring
 * [ModelRefScanner] — so it runs unchanged over a deployment `.bpmn`, a `.bar` archive entry, or a
 * flattened JSON export. The extracted URL is matched against code endpoints by [JavaParser.matchRest],
 * which already tolerates EL placeholders (`${...}`), scheme/host and query strings.
 */
object RestCallScanner {

    /** One outbound REST call: the raw `requestUrl` value, its end-inclusive offset [range] in the text,
     *  and the task's `requestMethod` (upper-case, or null when none is declared near the URL). */
    data class RestCall(val url: String, val range: IntRange, val method: String? = null)

    /** A model's outbound REST call reduced to what matching needs (no offset): URL + HTTP method. */
    data class RestRef(val url: String, val method: String?)

    /** Every outbound REST call in [text], each with the offset range of its (trimmed) URL value and
     *  the nearest declared `requestMethod`. */
    fun scan(text: String): List<RestCall> {
        val methods = fieldValues(text, "requestMethod")
        return fieldValues(text, "requestUrl").map { (url, range) ->
            RestCall(url, range, nearestMethod(methods, range.first))
        }
    }

    /** The distinct URL+method refs in [text] (offsets discarded) — for cheap index membership and
     *  method-aware matching. */
    fun refs(text: String): Set<RestRef> =
        scan(text).mapTo(LinkedHashSet()) { RestRef(it.url, it.method) }

    /** The `requestMethod` value nearest the URL at [urlStart] (within [METHOD_WINDOW]), or null — a
     *  task keeps its url/method fields adjacent, so nearest-by-offset pairs them without a real parse. */
    private fun nearestMethod(methods: List<Pair<String, IntRange>>, urlStart: Int): String? {
        val nearest = methods.minByOrNull { abs(it.second.first - urlStart) } ?: return null
        return if (abs(nearest.second.first - urlStart) <= METHOD_WINDOW) nearest.first.uppercase() else null
    }

    /** All non-empty values of the Flowable field [field], each with the offset range of the value. */
    private fun fieldValues(text: String, field: String): List<Pair<String, IntRange>> {
        val byStart = LinkedHashMap<Int, Pair<String, IntRange>>()
        for (re in fieldRegexes(field)) {
            for (m in re.findAll(text)) {
                val group = m.groups[1] ?: continue
                val value = group.value.trim()
                if (value.isEmpty()) continue
                byStart.putIfAbsent(group.range.first, value to group.range)
            }
        }
        return byStart.values.sortedBy { it.second.first }
    }

    /** The four value-carrying shapes a Flowable field [field] takes across BPMN XML and JSON exports.
     *  The JSON field object is matched name-first and value-first so key ordering does not matter. */
    private fun fieldRegexes(field: String): List<Regex> = listOf(
        // <flowable:field name="X" stringValue|expression="…"/>
        Regex("""name\s*=\s*"$field"\s+(?:stringValue|expression)\s*=\s*"([^"]*)""""),
        // <flowable:field name="X"><flowable:string|expression>[<![CDATA[]]>]…</…>
        Regex(
            """name\s*=\s*"$field"\s*>\s*<[A-Za-z0-9_:.]*(?:string|expression)\b[^>]*>\s*""" +
                """(?:<!\[CDATA\[)?\s*(.*?)\s*(?:]]>)?\s*</""",
            RegexOption.DOT_MATCHES_ALL,
        ),
        // Flattened JSON: a direct "X": "…" property.
        Regex(""""$field"\s*:\s*"([^"]*)""""),
        // JSON field object, name key first: {"name":"X","stringValue":"…"}.
        Regex(""""name"\s*:\s*"$field"\s*,\s*"(?:stringValue|string|expression|value)"\s*:\s*"([^"]*)""""),
        // JSON field object, value key first: {"stringValue":"…","name":"X"}.
        Regex(""""(?:stringValue|string|expression|value)"\s*:\s*"([^"]*)"\s*,\s*"name"\s*:\s*"$field""""),
    )

    private const val METHOD_WINDOW = 1000
}
