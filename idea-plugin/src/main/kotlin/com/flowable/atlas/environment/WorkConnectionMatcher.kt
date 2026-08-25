package com.flowable.atlas.environment

/**
 * Decides whether a base URL — typically one just parsed out of a pasted Flowable Work link — is one
 * of the environments the user has already defined.
 *
 * This is what turns pasting a URL into an environment switch instead of a silent detour into an
 * unnamed connection, so it has to be neither too strict nor too loose.
 *
 * *Too strict* would be exact equality: a connection registered as `https://work.example.com` would
 * not recognise a link to `https://work.example.com/flowable-work/#/…`, even though they are plainly
 * the same app, and the user would be asked to save a duplicate.
 *
 * *Too loose* would be plain string prefixing: `…/app` would swallow `…/app2`. So a prefix only counts
 * at a **path boundary**, and among several candidates the longest wins — the most specific answer,
 * which is the right one on a host serving two Flowable apps.
 *
 * Pure and unit-tested, like [com.flowable.atlas.expr.inspect.WorkUrlParser] whose output it consumes.
 */
object WorkConnectionMatcher {

    /** The connection [baseUrl] names, or null when it is not one of the defined environments. */
    fun match(baseUrl: String?, candidates: List<AtlasConnection>): AtlasConnection? {
        if (baseUrl.isNullOrBlank()) return null
        val key = BaseUrls.comparisonKey(ConnectionKind.WORK, baseUrl)
        val work = candidates.filter { it.kind == ConnectionKind.WORK }
        work.firstOrNull { BaseUrls.comparisonKey(ConnectionKind.WORK, it.baseUrl) == key }?.let { return it }
        return work
            .filter { relatedAtPathBoundary(BaseUrls.comparisonKey(ConnectionKind.WORK, it.baseUrl), key) }
            .maxByOrNull { BaseUrls.comparisonKey(ConnectionKind.WORK, it.baseUrl).length }
    }

    /** True when one of [a]/[b] is the other's prefix and the next character starts a new segment. */
    private fun relatedAtPathBoundary(a: String, b: String): Boolean =
        isPrefixAtBoundary(a, b) || isPrefixAtBoundary(b, a)

    private fun isPrefixAtBoundary(prefix: String, whole: String): Boolean =
        whole.length > prefix.length && whole.startsWith(prefix) && whole[prefix.length] == '/'
}
