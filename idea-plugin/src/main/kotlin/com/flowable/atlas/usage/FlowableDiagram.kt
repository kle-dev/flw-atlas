package com.flowable.atlas.usage

import com.flowable.atlas.model.ModelType
import com.intellij.openapi.vfs.VirtualFile

/**
 * Locates or produces the diagram to show for a Flowable model.
 *
 * A Flowable Design *export* used to ship a rendered `.svg` next to each model file
 * (`bpmn-models/onboarding.bpmn` → `bpmn-models/onboarding.svg`); [siblingSvg] finds that bundled
 * render and is still the preferred, best-fidelity source. Newer Design exports no longer bundle it,
 * so when there is no sibling `.svg` Atlas renders the diagram itself from the model's diagram-
 * interchange layout — see [DiagramSvgCache], which uses the shared `:core`
 * [com.flowable.atlas.diagram.DiagramRenderer]. A deployment BAR of a non-diagram model still yields
 * nothing, keeping the diagram gutter marker self-limiting.
 */
object FlowableDiagram {

    /** Model types Atlas can render a diagram for (process/case/decision have a BPMN/CMMN/DMN layout). */
    val DIAGRAM_TYPES: Set<ModelType> = setOf(ModelType.PROCESS, ModelType.CASE, ModelType.DECISION)

    /** True if a diagram could be rendered from this model type's layout (no parsing done here). */
    fun canRender(type: ModelType): Boolean = type in DIAGRAM_TYPES

    /**
     * The sibling `.svg` of [modelFile] — a file in the same directory whose name is the model file's
     * base name plus `.svg`, matched case-insensitively — or null when none exists (or the model file
     * has no parent directory). Works for a model file inside a jar/zip VFS too (it only walks the VFS
     * parent/children).
     */
    fun siblingSvg(modelFile: VirtualFile): VirtualFile? {
        val parent = modelFile.parent ?: return null
        val expected = baseName(modelFile.name) + ".svg"
        return parent.children?.firstOrNull { !it.isDirectory && it.name.equals(expected, ignoreCase = true) }
    }

    /** The model's base name — strips a compound `.bpmn20.xml`/`.cmmn.xml`/`.dmn.xml` suffix whole (so a
     *  deployment artifact's sibling is `<key>.svg`, not `<key>.bpmn20.svg`), else the last extension. */
    private fun baseName(fileName: String): String =
        ModelType.COMPOUND_EXTENSIONS.firstOrNull { fileName.endsWith(it, ignoreCase = true) }
            ?.let { fileName.dropLast(it.length) }
            ?: fileName.substringBeforeLast('.')

    /**
     * Whether a diagram gutter marker should appear for [modelFile] of [type] — a cheap check for the
     * highlighting pass: true if a bundled sibling `.svg` exists (any type) or the type is one Atlas can
     * render from its layout. The actual (possibly rendered) file is resolved lazily on click by
     * [DiagramSvgCache]; a process with no layout at all then simply reports "no diagram".
     */
    fun hasOpenableDiagram(modelFile: VirtualFile, type: ModelType): Boolean =
        canRender(type) || siblingSvg(modelFile) != null
}
