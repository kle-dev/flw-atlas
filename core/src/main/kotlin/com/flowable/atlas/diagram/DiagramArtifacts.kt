package com.flowable.atlas.diagram

import java.io.File

/**
 * The Atlas generation step that turns each model with a picture into a `<key>.svg` artifact — a
 * process, case or decision diagram, a form's or page's wireframe, a decision table drawn from its rules
 * when it has no layout — the same [ModelPicture] the explorer and the IDE show. It is a **read-only
 * post-pass over the finished `extract()` result** — it never mutates the result or the graph — so it
 * can be added to the `--all` / plugin generation output without perturbing the golden `extract()`
 * snapshot the tests pin. A model without a picture (no layout, another type) produces nothing.
 */
object DiagramArtifacts {

    /**
     * Render every node's picture. Returns `"<sanitized-key>.svg" → svg` (see
     * [uniqueName] for keys that clash), in graph-node order, skipping models whose file is unreadable or carries no drawable diagram.
     * [root] is the project root the node `file` paths are relative to.
     */
    fun render(
        result: Map<String, Any?>,
        root: File,
        /** Told about every model whose diagram could not be produced — an unreadable source or a
         *  renderer failure; a model that simply has no layout is not reported. */
        onFailure: ((key: String, reason: String) -> Unit)? = null,
    ): Map<String, String> {
        val graph = result["graph"] as? Map<*, *> ?: return emptyMap()
        val nodes = graph["nodes"] as? List<*> ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (nodeAny in nodes) {
            val node = nodeAny as? Map<*, *> ?: continue
            val type = ModelPicture.typeOfNode(node["type"] as? String) ?: continue
            val key = node["key"] as? String ?: continue
            val filePath = node["file"] as? String ?: continue
            // ModelBytes handles both loose files and "<archive>!<entry>" labels (see ModelBytes).
            val resolved = ModelBytes.resolve(root, filePath)
            if (resolved == null) { onFailure?.invoke(key, "model source could not be read from $filePath"); continue }
            val (bytes, name) = resolved
            val svg = runCatching { ModelPicture.render(bytes, name, type)?.svg }
                .onFailure { onFailure?.invoke(key, it.message ?: it.javaClass.simpleName) }
                .getOrNull() ?: continue
            out[uniqueName(sanitize(key), node["type"] as String, out.keys)] = svg
        }
        return out
    }

    /**
     * `<key>.svg`, unless that name is taken — compared ignoring case, since macOS and Windows file
     * systems would let `Order.svg` overwrite `order.svg`. A process and a case sharing a key, or two keys
     * that sanitize alike (`a/b`, `a_b`), then get `<type>-<key>.svg`, and a counter if even that clashes.
     * The first model keeps the plain name, so a project without clashes sees the names it always had.
     */
    private fun uniqueName(base: String, type: String, taken: Set<String>): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase() }
        fun free(name: String) = name.lowercase() !in lower
        "$base.svg".let { if (free(it)) return it }
        "$type-$base.svg".let { if (free(it)) return it }
        var i = 2
        while (!free("$type-$base-$i.svg")) i++
        return "$type-$base-$i.svg"
    }


    /** Keep the key filename-safe (keys are identifiers, but never let one escape the diagrams dir). */
    private fun sanitize(key: String): String = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
