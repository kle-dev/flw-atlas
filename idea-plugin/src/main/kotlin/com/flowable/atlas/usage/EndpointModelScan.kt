package com.flowable.atlas.usage

import com.flowable.atlas.index.FlowableIndex
import com.flowable.atlas.parsing.JavaParser
import com.flowable.atlas.parsing.RestCallScanner
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Links a Spring REST endpoint ([EndpointPsi.Endpoint]) to the Flowable model files that call it — the
 * endpoint counterpart to [ModelReferenceScan] (which links Java symbols). Every URL-carrying model
 * field counts, not just an HTTP service task's `requestUrl`: see [RestCallScanner.URL_FIELDS].
 *
 * Matching is the exact, tested `:core` [JavaParser.matchRest] used by the Atlas graph — path and verb —
 * so the IDE navigation and the generated explorer agree on what "a model calls this endpoint" means.
 * Only clean (segment-suffix, same verb) matches count; loose shared-last-segment matches and wrong-verb
 * hits are dropped to keep gutter navigation free of false links. A verb that is unknown on either side
 * (no `requestMethod`, an expression, a handler mapped to `ANY`) falls back to a path-only match.
 */
object EndpointModelScan {

    /** True when a model call [url] resolves to the endpoint [path] (clean, path-only match). */
    fun pathMatches(url: String, path: String): Boolean =
        JavaParser.matchRest(url, listOf(mapOf("path" to path))).any { it["loose"] != true }

    /** True when [call] hits [endpoint]: a clean path match and (when both verbs are concrete) same verb. */
    fun calls(call: RestCallScanner.RestRef, endpoint: EndpointPsi.Endpoint): Boolean =
        meaningful(endpoint) &&
            JavaParser.matchRest(call.url, listOf(mapOf("path" to endpoint.path, "http" to endpoint.verb)), call.method)
                .any { it["loose"] != true }

    /** A path worth matching — a blank / root `/` endpoint would match everything, so it is ignored. */
    private fun meaningful(endpoint: EndpointPsi.Endpoint): Boolean =
        endpoint.path.isNotBlank() && endpoint.path != "/"

    /** Cheap cached-index check: does any indexed model call [endpoint]? Drives the gutter pass. */
    fun anyModelCalls(index: FlowableIndex, endpoint: EndpointPsi.Endpoint): Boolean =
        index.restCalls.any { calls(it, endpoint) }

    /** Offset ranges in [text] of every model URL that hits one of [endpoints] — for Find Usages. */
    fun usageRanges(text: String, endpoints: List<EndpointPsi.Endpoint>): List<IntRange> =
        RestCallScanner.scan(text)
            .filter { c -> endpoints.any { calls(RestCallScanner.RestRef(c.url, c.method), it) } }
            .map { it.range }

    /**
     * Model files (and archive entries) calling one of [endpoints]. Takes the read lock only to list the
     * files, and must be called off the EDT. Returns empty for an empty endpoint list.
     */
    fun affectedModelFiles(project: Project, endpoints: List<EndpointPsi.Endpoint>): List<VirtualFile> =
        affectedModelUsages(project, endpoints).keys.toList()

    /** The same files, each with the offset of its first calling URL — what the gutter click opens at. */
    fun affectedModelUsages(project: Project, endpoints: List<EndpointPsi.Endpoint>): Map<VirtualFile, Int> {
        if (endpoints.none { meaningful(it) }) return emptyMap()
        val found = LinkedHashMap<VirtualFile, Int>()
        ModelReferenceScan.forEachModelTextUnlocked(project) { vf, text ->
            val first = usageRanges(text, endpoints).minOfOrNull { it.first } ?: return@forEachModelTextUnlocked
            found.putIfAbsent(vf, first)
        }
        return found
    }
}
