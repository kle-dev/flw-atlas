package com.flowable.atlas.usage

import com.flowable.atlas.diagram.DiagramRenderer
import com.flowable.atlas.diagram.DmnTableSvgRenderer
import com.flowable.atlas.model.ModelType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
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
 * so the IDE view and the generated files are identical. Failing that, a **decision table** is painted
 * from its rules ([DmnTableSvgRenderer]): a Design decision table has no canvas and therefore no `dmndi`
 * layout, so DI rendering finds nothing to draw — but the table is exactly what the reader wanted. A
 * generated diagram is returned as an in-memory [LightVirtualFile] named `<base>.svg`, which IntelliJ's
 * bundled Images viewer renders like any other SVG. Returns null when the model has no diagram at all
 * (non-diagram type, or a process/case with no layout).
 */
@Service(Service.Level.PROJECT)
class DiagramSvgCache {

    private val LOG = logger<DiagramSvgCache>()

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
        // Read failure is environmental (file deleted between index and paint) — debug. A *render*
        // failure is an Atlas defect on real customer DI and the only symptom is a missing gutter icon,
        // so it warns. Both are per-file-per-modification, not per-paint: the cache above bounds them.
        val bytes = runCatching { modelFile.contentsToByteArray() }
            .onFailure { LOG.debug("Could not read ${modelFile.path} to render its diagram", it) }
            .getOrNull() ?: return null
        val svg = runCatching { renderSvg(bytes, modelFile.name, type) }
            .onFailure { LOG.warn("Rendering the $type diagram for ${modelFile.path} failed", it) }
            .getOrNull() ?: return null
        val name = modelFile.name.substringBeforeLast('.') + ".svg"
        val file = LightVirtualFile(name, svg)
        cache[modelFile.url] = Rendered(stamp, file)
        return file
    }

    /**
     * DI layout first (a DRD, a process, a case), then — for a decision — the decision table itself.
     * XML only: a Design-workspace decision-table `.json` carries neither `dmndi` nor `<decisionTable>`.
     */
    private fun renderSvg(bytes: ByteArray, fileName: String, type: ModelType): String? =
        DiagramRenderer.renderSvg(bytes, fileName, type)
            ?: if (type == ModelType.DECISION && ModelType.isXmlModel(fileName)) {
                DmnTableSvgRenderer.renderSvg(bytes)
            } else {
                null
            }

    companion object {
        fun getInstance(project: Project): DiagramSvgCache = project.service()
    }
}
