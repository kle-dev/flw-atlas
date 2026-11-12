package com.flowable.atlas.index

import com.flowable.atlas.graph.Atlas
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelPaths
import com.flowable.atlas.model.ModelType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor

/**
 * Reads Flowable models packed inside a `.bar` / `.zip` archive (the real-world case where only the
 * business archive is checked in and the unpacked `…-bar/` folder is optional). The archive is
 * mounted explicitly via the `jar://…!/` protocol (works for any zip regardless of extension, with
 * no file-type registration), so each model entry is a navigable [VirtualFile] — usable for indexing
 * AND for go-to / Find-Usages.
 */
object ArchiveModelScanner {

    private val LOG = logger<ArchiveModelScanner>()

    fun isArchive(file: VirtualFile): Boolean =
        !file.isDirectory && ModelPaths.isArchive(file.name)

    /**
     * Whether a model of [length] bytes is read at all — the cap :core applies to every model, so the
     * index and Generate skip the same files. Without it the index read every entry of every archive in
     * full on project open: a cloned repository with a 1.5 MB archive holding a 1.5 GB model entry
     * allocated gigabytes in the IDE before anyone asked for anything.
     */
    fun readable(length: Long): Boolean = length <= Atlas.MAX_MODEL_BYTES

    /**
     * Invoke [consume] for every model entry (name, content, type, navigable file) inside [archive].
     *
     * [allowRefresh] mounts an archive the VFS has not seen yet, at the price of a **synchronous VFS
     * refresh** — fine for a one-off index build, but callers on a hot path (a per-keystroke search)
     * must pass `false`: refreshing from inside a read action risks a deadlock, and there it is
     * better to miss a never-yet-mounted archive than to freeze the IDE.
     */
    /** @return whether the archive could be opened at all — false for a non-archive, or one the jar
     *  file system cannot mount (corrupt, encrypted, or never refreshed when [allowRefresh] is off). */
    fun scan(
        archive: VirtualFile,
        allowRefresh: Boolean = true,
        consume: (String, ByteArray, ModelType, VirtualFile) -> Unit,
    ): Boolean {
        if (!isArchive(archive)) return false
        val jarFs = JarFileSystem.getInstance()
        val root = jarFs.getJarRootForLocalFile(archive)
            ?: (if (allowRefresh) jarFs.refreshAndFindFileByPath(archive.path + JarFileSystem.JAR_SEPARATOR) else null)
            ?: return false
        VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(entry: VirtualFile): Boolean {
                if (!entry.isDirectory) {
                    val type = ModelFiles.typeOf(entry)
                    if (type != null && !readable(entry.length)) {
                        LOG.debug("${entry.name} inside the archive is ${entry.length shr 20} MB, above the model limit — skipped")
                    } else if (type != null) {
                        // Debug: one unreadable entry (truncated/encrypted archive) drops that single
                        // model from the index. Per-entry inside a walk, so it must not warn.
                        runCatching { entry.contentsToByteArray() }
                            .onFailure { LOG.debug("Could not read ${entry.name} inside the archive — model skipped", it) }
                            .getOrNull()?.let { consume(entry.name, it, type, entry) }
                    }
                }
                return true
            }
        })
        return true
    }
}
