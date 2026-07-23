package com.flowable.atlas.usage

import com.flowable.atlas.index.FlowableIndex
import com.flowable.atlas.parsing.JavaParser
import com.flowable.atlas.parsing.RestCallScanner
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Links a Spring REST endpoint ([EndpointPsi.Endpoint]) to the Flowable model files whose HTTP
 * service tasks call it — the endpoint counterpart to [ModelReferenceScan] (which links Java symbols).
 *
 * Matching reuses the exact, tested `:core` [JavaParser.matchRest] used by the Atlas graph, so the IDE
 * navigation and the generated explorer agree on what "a model calls this endpoint" means. Only clean
 * (segment-suffix) matches count — the loose shared-last-segment matches the graph flags as suspect are
 * dropped here to keep gutter navigation free of false links.
 */
object EndpointModelScan {

    /** True when a model's `requestUrl` [url] resolves to the endpoint [path] (clean match only). */
    fun matches(url: String, path: String): Boolean =
        JavaParser.matchRest(url, listOf(mapOf("path" to path))).any { it["loose"] != true }

    /** Cheap cached-index check: does any indexed model call an endpoint at [path]? Drives the gutter pass. */
    fun anyModelCalls(index: FlowableIndex, path: String): Boolean =
        index.restCallUrls.any { matches(it, path) }

    /** Offset ranges in [text] of every `requestUrl` value that resolves to [path] — for Find Usages. */
    fun usageRanges(text: String, path: String): List<IntRange> =
        RestCallScanner.scan(text).filter { matches(it.url, path) }.map { it.range }

    /**
     * Model files (and archive entries) with an HTTP service task calling the endpoint at [path]. Runs
     * its own read action, so it must be called off the EDT. Returns empty for a blank/`/` path.
     */
    fun affectedModelFiles(project: Project, path: String): List<VirtualFile> {
        if (path.isBlank() || path == "/") return emptyList()
        return ReadAction.compute<List<VirtualFile>, RuntimeException> {
            if (project.isDisposed) return@compute emptyList()
            val found = LinkedHashSet<VirtualFile>()
            ModelReferenceScan.forEachModelText(project) { vf, text ->
                if (usageRanges(text, path).isNotEmpty()) found.add(vf)
            }
            found.toList()
        }
    }
}
