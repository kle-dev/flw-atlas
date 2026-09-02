package com.flowable.atlas.index

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
                    if (type != null) {
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
