package com.flowable.atlas.diagram

import com.flowable.atlas.model.ModelType
import java.io.File

/**
 * The Atlas generation step that turns each diagram-bearing model into a `<key>.svg` artifact. It is a
 * **read-only post-pass over the finished `extract()` result** — it never mutates the result or the
 * graph — so it can be added to the `--all` / plugin generation output without perturbing the golden
 * `extract()` snapshot the tests pin. Models with no diagram (no DI, or a non-diagram type) simply
 * produce nothing, so a project without any BPMN/CMMN/DMN layout emits no diagram files at all.
 */
object DiagramArtifacts {

    /**
     * Render every process/case/decision node's diagram. Returns `"<sanitized-key>.svg" → svg`, in
     * graph-node order, skipping models whose file is unreadable or carries no drawable diagram.
     * [root] is the project root the node `file` paths are relative to.
     */
    fun render(result: Map<String, Any?>, root: File): Map<String, String> {
        val graph = result["graph"] as? Map<*, *> ?: return emptyMap()
        val nodes = graph["nodes"] as? List<*> ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (nodeAny in nodes) {
            val node = nodeAny as? Map<*, *> ?: continue
            val type = modelType(node["type"] as? String) ?: continue
            val key = node["key"] as? String ?: continue
            val filePath = node["file"] as? String ?: continue
            val file = File(root, filePath)
            val bytes = runCatching { if (file.isFile) file.readBytes() else null }.getOrNull() ?: continue
            val svg = runCatching { DiagramRenderer.renderSvg(bytes, file.name, type) }.getOrNull() ?: continue
            out.putIfAbsent(sanitize(key) + ".svg", svg)
        }
        return out
    }

    private fun modelType(nodeType: String?): ModelType? = when (nodeType) {
        "process" -> ModelType.PROCESS
        "case" -> ModelType.CASE
        "decision" -> ModelType.DECISION
        else -> null
    }

    /** Keep the key filename-safe (keys are identifiers, but never let one escape the diagrams dir). */
    private fun sanitize(key: String): String = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
