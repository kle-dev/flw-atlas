package com.flowable.atlas.parsing

import kotlin.math.abs

/**
 * Locates the outbound REST calls a model file makes, together with the exact offset range of each URL
 * value in the text. Companion to [ModelUsageLocator] (which locates Java-symbol references): this one
 * locates the endpoint URLs so a Spring controller's `@GetMapping` can find the models that call it.
 *
 * Four kinds of model carry an outbound URL, and all four must be found — scanning only the BPMN/CMMN
 * HTTP task's `requestUrl` (as this did originally) made the IDE disagree with the generated explorer,
 * which resolves all four: a form/page REST button and a `.service` operation were called out in the
 * report but had no gutter icon and no Find Usages hit.
 *
 * Deliberately loose, pure text scanning (no XML/JSON parse, no I/O, no IntelliJ) — mirroring
 * [ModelRefScanner] — so it runs unchanged over a deployment `.bpmn`, a `.bar` archive entry, or a
 * flattened JSON export. The extracted URL is matched against code endpoints by [JavaParser.matchRest],
 * which already tolerates placeholders (`${…}`, `{{…}}`, `{…}`), scheme/host and query strings.
 */
object RestCallScanner {

    /** One outbound REST call: the raw URL value, its end-inclusive offset [range] in the text, the
     *  declared HTTP method (upper-case, or null when none is declared near the URL), and the model
     *  [field] the URL was read from (`requestUrl`, `url`, …). */
    data class RestCall(val url: String, val range: IntRange, val method: String? = null, val field: String = "requestUrl")

    /** A model's outbound REST call reduced to what matching needs (no offset): URL + HTTP method. */
    data class RestRef(val url: String, val method: String?)

    /**
     * The model fields that hold an outbound URL, most specific first.
     *
     * - `requestUrl` — BPMN/CMMN HTTP service task (`flowable:type="http"`), a `flowable:field`.
     * - `url` — a form/page REST button's `extraSettings.url`, and a `.service` operation's `config.url`.
     * - `queryUrl` / `lookupUrl` — a select's or data table's REST data source.
     *
     * `url` is a common JSON key, so this does pull in unrelated values (icon URLs, link targets). That
     * costs index size only: [com.flowable.atlas.usage.EndpointModelScan] keeps a call solely when
     * [JavaParser.matchRest] reports a *clean* segment-suffix match against a real controller path, so a
     * stray URL cannot produce a wrong navigation target.
     */
    val URL_FIELDS = listOf("requestUrl", "url", "queryUrl", "lookupUrl")

    /** The fields that hold the HTTP verb, paired with a URL by proximity. */
    private val METHOD_FIELDS = listOf("requestMethod", "method")

    /** Every outbound REST call in [text], each with the offset range of its (trimmed) URL value and
     *  the nearest declared method. */
    fun scan(text: String): List<RestCall> {
        val methods = METHOD_FIELDS.flatMap { fieldValues(text, it) }.sortedBy { it.second.first }
        return URL_FIELDS.flatMap { field ->
            fieldValues(text, field).map { (url, range) -> RestCall(url, range, nearestMethod(methods, range.first, field), field) }
        }
            // The field names cannot overlap (the regexes anchor on the opening quote, so `"url"` never
            // matches inside `"requestUrl"`), but one offset yielding one call is the invariant callers
            // rely on — `usageRanges` would otherwise report a duplicate highlight.
            .distinctBy { it.range.first }
            .sortedBy { it.range.first }
    }

    /** The distinct URL+method refs in [text] (offsets discarded) — for cheap index membership and
     *  method-aware matching. */
    fun refs(text: String): Set<RestRef> =
        scan(text).mapTo(LinkedHashSet()) { RestRef(it.url, it.method) }

    /**
     * The method value nearest the URL at [urlStart], or null.
     *
     * An HTTP task keeps its `requestUrl`/`requestMethod` fields adjacent but with a whole
     * `<extensionElements>` block's worth of text between them, hence the generous [METHOD_WINDOW].
     * The other URL fields sit in a compact `extraSettings` / `config` object next to their `method`, so
     * they use [ES_METHOD_WINDOW]: with several buttons per form, a wide window would attach a
     * *neighbour's* verb and `EndpointModelScan` would then reject an otherwise valid match. Returning
     * null is the safe direction — an unknown verb matches any endpoint verb.
     */
    private fun nearestMethod(methods: List<Pair<String, IntRange>>, urlStart: Int, field: String): String? {
        val window = if (field == "requestUrl") METHOD_WINDOW else ES_METHOD_WINDOW
        val nearest = methods.minByOrNull { abs(it.second.first - urlStart) } ?: return null
        return if (abs(nearest.second.first - urlStart) <= window) nearest.first.uppercase() else null
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

    /** The value-carrying shapes a Flowable field [field] takes across BPMN XML and JSON exports.
     *  Both the XML attribute form and the JSON field object are matched name-first and value-first so
     *  key ordering does not matter. */
    private fun fieldRegexes(field: String): List<Regex> = listOf(
        // <flowable:field name="X" stringValue|expression="…"/>
        Regex("""name\s*=\s*"$field"\s+(?:stringValue|expression)\s*=\s*"([^"]*)""""),
        // <flowable:field stringValue|expression="…" name="X"/>
        Regex("""(?:stringValue|expression)\s*=\s*"([^"]*)"\s+name\s*=\s*"$field""""),
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
    private const val ES_METHOD_WINDOW = 120
}
