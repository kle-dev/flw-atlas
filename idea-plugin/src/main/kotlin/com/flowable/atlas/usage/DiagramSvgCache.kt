package com.flowable.atlas.usage

import com.flowable.atlas.diagram.ModelPicture
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.diagram.Picture
import com.flowable.atlas.model.ModelType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile

/**
 * Resolves the diagram [VirtualFile] to open for a Flowable model, and caches any SVG it has to render
 * so re-opening the same (unchanged) model is instant.
 *
 * Resolution order: the model's **bundled sibling `.svg`** (an older Design export — best fidelity),
 * otherwise a **freshly rendered picture** from the shared `:core` [ModelPicture] — the diagram from
 * the model's diagram-interchange layout, a decision table from its rules when it has no layout, a form
 * or page as a wireframe of its grid — the same dispatch the Atlas explorer and the generated diagrams
 * folder use, so the IDE view and the generated files are identical. A generated diagram is returned as
 * an in-memory [LightVirtualFile] named `<base>.svg`, which IntelliJ's
 * bundled Images viewer renders like any other SVG. Returns null when the model has no diagram at all
 * (another model type, a process/case with no layout, or a form that is not one).
 *
 * A form is drawn with the forms its subforms embed, found by key in the model index — so a cached
 * wireframe is also stale once one of those files changed, or once the index it was drawn without lands.
 */
@Service(Service.Level.PROJECT)
class DiagramSvgCache(private val project: Project) {

    private val LOG = logger<DiagramSvgCache>()

    private data class Rendered(val stamp: Long, val picture: Picture, val file: VirtualFile, val subforms: Subforms) {
        fun current(stamp: Long, indexed: Boolean): Boolean =
            this.stamp == stamp && subforms.read.all { (f, s) -> f.isValid && f.modificationStamp == s } &&
                !(subforms.awaitingIndex && indexed)
    }

    /**
     * A form's subforms by key, from the model index — what [ModelPicture.render] draws inside the form.
     * Remembers every file it read, and whether a subform went unresolved only because the index was not
     * built yet: it never waits for one, since a gutter icon asks from a paint.
     */
    internal inner class Subforms {
        val read = LinkedHashMap<VirtualFile, Long>()
        var awaitingIndex = false
            private set

        fun resolve(key: String): ByteArray? {
            val index = project.service<FlowableModelIndexService>().cachedOrRequest()
            if (index == null) { awaitingIndex = true; return null }
            val f = index.find(key, ModelType.FORM)?.file ?: return null
            read[f] = f.modificationStamp
            return runCatching { f.contentsToByteArray() }.getOrNull()
        }
    }

    // A bounded, most-recently-used cache: every rendered SVG is a string the size of the drawing, and
    // an unbounded map kept each one for the life of the session.
    private val cache = object : LinkedHashMap<String, Rendered>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Rendered>): Boolean = size > MAX_ENTRIES
    }
    private fun cached(url: String): Rendered? = synchronized(cache) { cache[url] }
    private fun remember(url: String, r: Rendered) { synchronized(cache) { cache[url] = r } }

    fun resolveDiagram(modelFile: VirtualFile, type: ModelType): VirtualFile? {
        FlowableDiagram.siblingSvg(modelFile)?.let { return it }
        if (!FlowableDiagram.canRender(type)) return null
        return rendered(modelFile, type)?.file
    }

    /** The picture Atlas draws for [modelFile] — never a bundled export SVG, which comes without the
     *  places of its elements — or null when the model has none. */
    fun resolvePicture(modelFile: VirtualFile, type: ModelType): Picture? =
        if (FlowableDiagram.canRender(type)) rendered(modelFile, type)?.picture else null

    /** The files of the subforms the cached picture of [modelFile] draws inside it. */
    fun embeddedFiles(modelFile: VirtualFile): Set<VirtualFile> = cached(modelFile.url)?.subforms?.read?.keys?.toSet().orEmpty()

    private fun rendered(modelFile: VirtualFile, type: ModelType): Rendered? {
        val stamp = modelFile.modificationStamp
        cached(modelFile.url)?.let { if (it.current(stamp, project.service<FlowableModelIndexService>().cachedOrNull() != null)) return it }
        // Read failure is environmental (file deleted between index and paint) — debug. A *render*
        // failure is an Atlas defect on real customer DI and the only symptom is a missing gutter icon,
        // so it warns. Both are per-file-per-modification, not per-paint: the cache above bounds them.
        val bytes = runCatching { modelFile.contentsToByteArray() }
            .onFailure { LOG.debug("Could not read ${modelFile.path} to render its diagram", it) }
            .getOrNull() ?: return null
        val subforms = Subforms()
        val picture = runCatching { picture(bytes, modelFile.name, type, subforms) }
            .onFailure { LOG.warn("Rendering the $type diagram for ${modelFile.path} failed", it) }
            .getOrNull() ?: return null
        val name = modelFile.name.substringBeforeLast('.') + ".svg"
        val r = Rendered(stamp, picture, LightVirtualFile(name, picture.svg), subforms)
        remember(modelFile.url, r)
        return r
    }

    /** The picture of a model's [bytes] — the shared `:core` dispatch, see [ModelPicture]; a form's
     *  subforms come from the model index. */
    internal fun picture(bytes: ByteArray, fileName: String, type: ModelType, subforms: Subforms = Subforms()): Picture? =
        ModelPicture.render(bytes, fileName, type, subforms::resolve)

    internal fun renderSvg(bytes: ByteArray, fileName: String, type: ModelType): String? = picture(bytes, fileName, type)?.svg

    companion object {
        fun getInstance(project: Project): DiagramSvgCache = project.service()

        /** How many rendered drawings are kept — the most recently used ones. */
        private const val MAX_ENTRIES = 32
    }
}
