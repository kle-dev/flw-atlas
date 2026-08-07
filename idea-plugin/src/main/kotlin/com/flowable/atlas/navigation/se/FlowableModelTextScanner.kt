package com.flowable.atlas.navigation.se

import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.events.AtlasEventsListener
import com.flowable.atlas.index.ArchiveModelScanner
import com.flowable.atlas.model.ModelFiles
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import java.lang.ref.SoftReference
import java.util.concurrent.ConcurrentHashMap

/**
 * The text source behind the full-text half of the "Flowable Model" tab: every model file in the
 * project — archive entries expanded — plus its decoded content, cheap enough to re-scan on every
 * keystroke.
 *
 * Two things make that affordable. First, the same two-phase split
 * [com.flowable.atlas.index.FlowableModelIndexService] uses: only the VFS walk takes the read lock,
 * the reading and decoding runs without it. Second, both the file list and the decoded texts are
 * cached until the model index reports a change, so only the first keystroke of a session pays for
 * I/O.
 *
 * Scope matches the index: model-typed files only (`ModelFiles.typeOf`), so non-model archive
 * entries — `app.json`, changelogs, images — stay out.
 */
@Service(Service.Level.PROJECT)
class FlowableModelTextScanner(private val project: Project) : Disposable {

    private class CachedText(val stamp: Long, val text: String)

    @Volatile private var files: List<VirtualFile>? = null
    private val texts = ConcurrentHashMap<String, SoftReference<CachedText>>()

    init {
        project.messageBus.connect(this).subscribe(
            AtlasEvents.TOPIC,
            object : AtlasEventsListener {
                override fun modelIndexUpdated() = invalidate()
            },
        )
    }

    fun invalidate() {
        files = null
        texts.clear()
    }

    /**
     * Feeds every model file and its text to [consumer] until it returns `false` or [indicator] is
     * cancelled. Call off the EDT — the first call walks the project's content roots.
     */
    fun forEachText(indicator: ProgressIndicator, consumer: (VirtualFile, String) -> Boolean) {
        for (file in modelFiles(indicator)) {
            indicator.checkCanceled()
            val text = textOf(file) ?: continue
            if (!consumer(file, text)) return
        }
    }

    /** Every model file, archive entries expanded. Cached until the index reports a change. */
    private fun modelFiles(indicator: ProgressIndicator): List<VirtualFile> {
        files?.let { return it }
        val expanded = ArrayList<VirtualFile>()
        for (candidate in collectCandidates()) {
            indicator.checkCanceled()
            if (ArchiveModelScanner.isArchive(candidate)) {
                // No refresh fallback: a synchronous VFS refresh per keystroke is a freeze waiting
                // to happen. An archive the VFS has never mounted is simply skipped this round —
                // the index build (which does refresh) mounts it and invalidates us.
                ArchiveModelScanner.scan(candidate, allowRefresh = false) { _, bytes, _, entry ->
                    expanded.add(entry)
                    texts[entry.url] = SoftReference(CachedText(entry.modificationStamp, String(bytes, Charsets.UTF_8)))
                }
            } else {
                expanded.add(candidate)
            }
        }
        files = expanded
        return expanded
    }

    /** Phase 1 — the read-locked part: which files are worth reading. Touches no content. */
    private fun collectCandidates(): List<VirtualFile> =
        ReadAction.compute<List<VirtualFile>, RuntimeException> {
            if (project.isDisposed) return@compute emptyList()
            val out = ArrayList<VirtualFile>()
            ProjectFileIndex.getInstance(project).iterateContent { file ->
                if (!file.isDirectory && !ModelFiles.isExcluded(file.path) &&
                    (ModelFiles.typeOf(file) != null || ArchiveModelScanner.isArchive(file))
                ) {
                    out.add(file)
                }
                !project.isDisposed
            }
            out
        }

    /** Decoded content of [file], from the cache when it is still current. */
    private fun textOf(file: VirtualFile): String? {
        if (!file.isValid) return null
        val stamp = file.modificationStamp
        texts[file.url]?.get()?.let { if (it.stamp == stamp) return it.text }
        val text = runCatching { String(file.contentsToByteArray(), Charsets.UTF_8) }.getOrNull() ?: return null
        texts[file.url] = SoftReference(CachedText(stamp, text))
        return text
    }

    override fun dispose() {
        files = null
        texts.clear()
    }
}
