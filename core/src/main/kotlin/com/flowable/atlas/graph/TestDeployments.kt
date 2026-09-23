package com.flowable.atlas.graph

import com.flowable.atlas.parsing.JavaParser
import java.io.File

/**
 * Which tests deploy a model: `@Deployment(resources = "bpmn/order.bpmn20.xml")` and its CMMN, DMN and
 * app siblings on a test method or class. Test code is not the project — it adds no Java node, no
 * variable site, no reference — but that a model is exercised by a test is worth knowing on the model's
 * page, and until now nothing said it. Recorded as `deployedByTests` (`file:line` of each annotation).
 *
 * A resource is a classpath path, so it names a model whose project path ends with it, loose or as an
 * archive entry. A resource that names no model is left alone: it may be a fixture the build copies in.
 */
internal object TestDeployments {

    private val ANNOTATION_RE = Regex("""@(?:Cmmn|Dmn|App|Form|Event)?Deployment\s*\(([^)]*)\)""")
    private val RESOURCES_RE = Regex("""\bresources\s*=\s*(\{[^}]*}|"[^"]*")""")
    private val STRING_RE = Regex(""""([^"]+)"""")

    @Suppress("UNCHECKED_CAST")
    fun apply(result: Map<String, Any?>, testSources: List<File>, relOf: (File) -> String) {
        val nodes = ((result["graph"] as? Map<*, *>)?.get("nodes") as? List<*>)?.mapNotNull { it as? Map<String, Any?> }.orEmpty()
        // model path (an archive entry by its entry path) → the nodes it holds
        val byPath = HashMap<String, MutableList<Map<String, Any?>>>()
        for (n in nodes) {
            val file = n["file"] as? String ?: continue
            if (n["data"] !is MutableMap<*, *>) continue
            byPath.getOrPut(file.substringAfterLast('!')) { ArrayList() }.add(n)
        }
        if (byPath.isEmpty()) return
        for (f in testSources) {
            if (f.length() > Atlas.MAX_MODEL_BYTES) continue
            val text = runCatching { JavaParser.blankComments(f.readText(Charsets.UTF_8)) }.getOrNull() ?: continue
            if ("Deployment" !in text) continue
            val rel = relOf(f)
            for (a in ANNOTATION_RE.findAll(text)) {
                val resources = RESOURCES_RE.find(a.groupValues[1])?.groupValues?.get(1) ?: continue
                val at = "$rel:${text.substring(0, a.range.first).count { it == '\n' } + 1}"
                for (res in STRING_RE.findAll(resources).map { it.groupValues[1].trimStart('/') }) {
                    for ((path, held) in byPath) {
                        if (path != res && !path.endsWith("/$res")) continue
                        for (n in held) {
                            val data = n["data"] as MutableMap<String, Any?>
                            val prev = (data["deployedByTests"] as? List<String>).orEmpty()
                            if (at !in prev) data["deployedByTests"] = prev + at
                        }
                    }
                }
            }
        }
    }
}
