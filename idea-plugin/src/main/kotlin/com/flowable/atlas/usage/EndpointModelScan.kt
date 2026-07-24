package com.flowable.atlas.usage

import com.flowable.atlas.index.FlowableIndex
import com.flowable.atlas.parsing.JavaParser
import com.flowable.atlas.parsing.RestCallScanner
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Links a Spring REST endpoint ([EndpointPsi.Endpoint]) to the Flowable model files whose HTTP service
 * tasks call it — the endpoint counterpart to [ModelReferenceScan] (which links Java symbols).
 *
 * Path matching reuses the exact, tested `:core` [JavaParser.matchRest] used by the Atlas graph, so the
 * IDE navigation and the generated explorer agree on what "a model calls this endpoint" means. Only
 * clean (segment-suffix) matches count; the loose shared-last-segment matches are dropped to keep
 * gutter navigation free of false links. On top of the path, the model's `requestMethod` is compared
 * to the endpoint verb **when both are concrete** — so a GET task no longer lights up a POST-only
 * handler on the same path — while an unknown verb on either side falls back to a path-only match.
 */
object EndpointModelScan {

    /** True when a model call [url] resolves to the endpoint [path] (clean, path-only match). */
    fun pathMatches(url: String, path: String): Boolean =
        JavaParser.matchRest(url, listOf(mapOf("path" to path))).any { it["loose"] != true }

    /** True when [call] hits [endpoint]: a clean path match and (when both verbs are concrete) same verb. */
    fun calls(call: RestCallScanner.RestRef, endpoint: EndpointPsi.Endpoint): Boolean =
        meaningful(endpoint) && pathMatches(call.url, endpoint.path) && verbMatches(call.method, endpoint.verb)

    /** Model verb vs endpoint verb: only discriminates when both are known (endpoint ≠ `ANY`, model set). */
    private fun verbMatches(modelMethod: String?, endpointVerb: String): Boolean =
        endpointVerb == "ANY" || modelMethod.isNullOrBlank() || modelMethod.equals(endpointVerb, ignoreCase = true)

    /** A path worth matching — a blank / root `/` endpoint would match everything, so it is ignored. */
    private fun meaningful(endpoint: EndpointPsi.Endpoint): Boolean =
        endpoint.path.isNotBlank() && endpoint.path != "/"

    /** Cheap cached-index check: does any indexed model call [endpoint]? Drives the gutter pass. */
    fun anyModelCalls(index: FlowableIndex, endpoint: EndpointPsi.Endpoint): Boolean =
        index.restCalls.any { calls(it, endpoint) }

    /** Offset ranges in [text] of every `requestUrl` that hits one of [endpoints] — for Find Usages. */
    fun usageRanges(text: String, endpoints: List<EndpointPsi.Endpoint>): List<IntRange> =
        RestCallScanner.scan(text)
            .filter { c -> endpoints.any { calls(RestCallScanner.RestRef(c.url, c.method), it) } }
            .map { it.range }

    /**
     * Model files (and archive entries) with an HTTP service task calling one of [endpoints]. Runs its
     * own read action, so it must be called off the EDT. Returns empty for an empty endpoint list.
     */
    fun affectedModelFiles(project: Project, endpoints: List<EndpointPsi.Endpoint>): List<VirtualFile> {
        if (endpoints.none { meaningful(it) }) return emptyList()
        return ReadAction.compute<List<VirtualFile>, RuntimeException> {
            if (project.isDisposed) return@compute emptyList()
            val found = LinkedHashSet<VirtualFile>()
            ModelReferenceScan.forEachModelText(project) { vf, text ->
                if (usageRanges(text, endpoints).isNotEmpty()) found.add(vf)
            }
            found.toList()
        }
    }
}
