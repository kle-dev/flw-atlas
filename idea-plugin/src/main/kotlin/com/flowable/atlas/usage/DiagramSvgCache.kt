package com.flowable.atlas.usage

import com.flowable.atlas.diagram.DiagramRenderer
import com.flowable.atlas.model.ModelType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the diagram [VirtualFile] to open for a Flowable model, and caches any SVG it has to render
 * so re-opening the same (unchanged) model is instant.
 *
 * Resolution order: the model's **bundled sibling `.svg`** (an older Design export — best fidelity),
 * otherwise a **freshly rendered SVG** from the model's diagram-interchange layout via the shared
 * `:core` [DiagramRenderer] — the same engine the Atlas generation uses to emit its diagram artifacts,
 * so the IDE view and the generated files are identical. A generated diagram is returned as an
 * in-memory [LightVirtualFile] named `<base>.svg`, which IntelliJ's bundled Images viewer renders like
 * any other SVG. Returns null when the model has no diagram (non-diagram type, or no layout in it).
 */
@Service(Service.Level.PROJECT)
class DiagramSvgCache {

    private data class Rendered(val stamp: Long, val file: VirtualFile)

    private val cache = ConcurrentHashMap<String, Rendered>()

    fun resolveDiagram(modelFile: VirtualFile, type: ModelType): VirtualFile? {
        FlowableDiagram.siblingSvg(modelFile)?.let { return it }
        if (!FlowableDiagram.canRender(type)) return null
        return rendered(modelFile, type)
    }

    private fun rendered(modelFile: VirtualFile, type: ModelType): VirtualFile? {
        val stamp = modelFile.modificationStamp
        cache[modelFile.url]?.let { if (it.stamp == stamp) return it.file }
        val bytes = runCatching { modelFile.contentsToByteArray() }.getOrNull() ?: return null
        val svg = runCatching { DiagramRenderer.renderSvg(bytes, modelFile.name, type) }.getOrNull() ?: return null
        val name = modelFile.name.substringBeforeLast('.') + ".svg"
        val file = LightVirtualFile(name, svg)
        cache[modelFile.url] = Rendered(stamp, file)
        return file
    }

    companion object {
        fun getInstance(project: Project): DiagramSvgCache = project.service()
    }
}
