package com.flowable.atlas.parsing

/**
 * Locates the outbound REST calls a model file makes — the `requestUrl` value of an HTTP service
 * task (`flowable:type="http"`) — together with the exact offset range of that value in the text.
 * Companion to [ModelUsageLocator] (which locates Java-symbol references): this one locates the
 * endpoint URLs so a Spring controller's `@GetMapping` can find the models that call it.
 *
 * Deliberately loose, pure text scanning (no XML/JSON parse, no I/O, no IntelliJ) — mirroring
 * [ModelRefScanner] — so it runs unchanged over a deployment `.bpmn`, a `.bar` archive entry, or a
 * flattened JSON export. The extracted URL is matched against code endpoints by [JavaParser.matchRest],
 * which already tolerates EL placeholders (`${...}`), scheme/host and query strings.
 */
object RestCallScanner {

    /** One outbound REST call: the raw `requestUrl` value and its end-inclusive offset range in the text. */
    data class RestCall(val url: String, val range: IntRange)

    // Field-injection value given as an attribute: <flowable:field name="requestUrl" stringValue="…"/>.
    private val XML_ATTR = Regex("""name\s*=\s*"requestUrl"\s+(?:stringValue|expression)\s*=\s*"([^"]*)"""")

    // Field-injection value given as a child element (with or without CDATA):
    //   <flowable:field name="requestUrl"><flowable:string><![CDATA[…]]></flowable:string></flowable:field>
    //   <flowable:field name="requestUrl"><flowable:expression>…</flowable:expression></flowable:field>
    private val XML_NESTED = Regex(
        """name\s*=\s*"requestUrl"\s*>\s*<[A-Za-z0-9_:.]*(?:string|expression)\b[^>]*>\s*""" +
            """(?:<!\[CDATA\[)?\s*(.*?)\s*(?:]]>)?\s*</""",
        RegexOption.DOT_MATCHES_ALL,
    )

    // Flattened JSON export: a direct "requestUrl": "…" property.
    private val JSON_DIRECT = Regex(""""requestUrl"\s*:\s*"([^"]*)"""")

    // JSON field object: {"name": "requestUrl", "stringValue": "…"}.
    private val JSON_FIELD =
        Regex(""""name"\s*:\s*"requestUrl"\s*,\s*"(?:stringValue|string|expression|value)"\s*:\s*"([^"]*)"""")

    /** Every outbound REST call in [text], each with the offset range of its (trimmed) URL value. */
    fun scan(text: String): List<RestCall> {
        val byStart = LinkedHashMap<Int, RestCall>()
        for (re in listOf(XML_ATTR, XML_NESTED, JSON_DIRECT, JSON_FIELD)) {
            for (m in re.findAll(text)) {
                val group = m.groups[1] ?: continue
                val url = group.value.trim()
                if (url.isEmpty()) continue
                byStart.putIfAbsent(group.range.first, RestCall(url, group.range))
            }
        }
        return byStart.values.sortedBy { it.range.first }
    }

    /** The distinct `requestUrl` values in [text] (offsets discarded) — for cheap set-membership indexing. */
    fun urls(text: String): Set<String> = scan(text).mapTo(LinkedHashSet()) { it.url }
}
