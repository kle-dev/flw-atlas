package com.flowable.atlas.render

import com.flowable.atlas.model.MiniJson
import java.io.File

/**
 * The self-contained, offline interactive HTML explorer.
 *
 * Port of `flowable_atlas.py` `html_render` + `_compose_template` + `_frontend_asset`. Instead of
 * Python's `repr()`-embedded `_EMBEDDED_FRONTEND`, the three frontend files are bundled into `:core`
 * resources (`/frontend/explorer.{html,css,js}`, copied from the repo's `frontend/`) and read at
 * runtime via the classloader. Composition matches Python exactly: inline the CSS into the
 * `/*__ATLAS_CSS__*/` marker, the JS into `/*__ATLAS_JS__*/`, `rstrip` trailing newlines, then
 * substitute the graph-JSON island into `__ATLAS_DATA__`.
 */
object ExplorerHtmlRenderer {

    @Suppress("UNCHECKED_CAST")
    fun render(result: Map<String, Any?>, root: File): String {
        val graph = result["graph"] as Map<String, Any?>
        // Same payload object html_render builds, in the same key order.
        val payload = LinkedHashMap<String, Any?>()
        payload["project"] = root.absoluteFile.name.ifEmpty { "project" }
        payload["stats"] = result["stats"]
        payload["diagnostics"] = result["diagnostics"] ?: ArrayList<Any?>()
        payload["customFunctions"] = result["customFunctions"]
        payload["nodes"] = graph["nodes"]
        payload["edges"] = graph["edges"]
        // json.dumps(payload, ensure_ascii=False, default=list).replace("</", "<\/")
        val data = MiniJson.stringify(payload).replace("</", "<\\/")
        return composeTemplate().replace("__ATLAS_DATA__", data)
    }

    /** The full explorer HTML page (CSS/JS inlined; `__ATLAS_DATA__` still unresolved). */
    private fun composeTemplate(): String {
        var t = asset("explorer.html")
        t = t.replace("/*__ATLAS_CSS__*/", asset("explorer.css"))
        t = t.replace("/*__ATLAS_JS__*/", asset("explorer.js"))
        return t.trimEnd('\n')
    }

    private fun asset(name: String): String {
        val stream = ExplorerHtmlRenderer::class.java.getResourceAsStream("/frontend/$name")
            ?: error("frontend asset /frontend/$name not on the classpath")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
