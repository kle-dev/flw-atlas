package com.flowable.atlas.diagram

import java.io.File
import java.util.zip.ZipFile

/**
 * Resolves the raw bytes of a model given the `file` label the graph carries for it — the single place
 * the diagram post-passes ([DiagramArtifacts], [com.flowable.atlas.render.ExplorerHtmlRenderer]) read a
 * model back off disk to render its SVG.
 *
 * A graph node's `file` is the label [Atlas.extract][com.flowable.atlas.graph.Atlas] assigned when it
 * discovered the model, and there are two shapes (see `Extractor`): a plain forward-slash relative path
 * for a **loose** model file, and `"<archiveRel>!<entryPath>"` for a model that lives **inside an
 * archive** (`.zip`/`.bar`/Design app export). A naive `File(root, label)` resolves the loose form but
 * silently fails on the archive form — `root/app.zip!bpmn/foo.bpmn20.xml` is not a real path — which is
 * why archived models used to get no diagram in the generated explorer. This helper mirrors the two
 * branches `Extractor` used to *read* those bytes, so both forms round-trip.
 */
object ModelBytes {

    /**
     * @return the model's `(bytes, baseFileName)` — the base file name being what
     * [DiagramRenderer.renderSvg] needs to pick the XML-vs-JSON DI source — or null when the label
     * cannot be resolved to readable bytes (missing file, missing/broken archive or entry).
     */
    fun resolve(root: File, fileLabel: String): Pair<ByteArray, String>? {
        val bang = fileLabel.indexOf('!') // Extractor archive label: "$rel!${entry.name}"
        if (bang >= 0) {
            val archive = File(root, fileLabel.substring(0, bang))
            val entryName = fileLabel.substring(bang + 1)
            if (!archive.isFile) return null
            return runCatching {
                ZipFile(archive).use { zf ->
                    val entry = zf.getEntry(entryName) ?: return null
                    val bytes = zf.getInputStream(entry).use { it.readBytes() }
                    bytes to entryName.substringAfterLast('/')
                }
            }.getOrNull()
        }
        val f = File(root, fileLabel)
        return if (f.isFile) runCatching { f.readBytes() }.getOrNull()?.let { it to f.name } else null
    }
}
