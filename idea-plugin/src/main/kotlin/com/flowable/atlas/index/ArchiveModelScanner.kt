package com.flowable.atlas.index

import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelPaths
import com.flowable.atlas.model.ModelType
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

    fun isArchive(file: VirtualFile): Boolean =
        !file.isDirectory && ModelPaths.isArchive(file.name)

    /** Invoke [consume] for every model entry (name, content, type, navigable file) inside [archive]. */
    fun scan(archive: VirtualFile, consume: (String, ByteArray, ModelType, VirtualFile) -> Unit) {
        if (!isArchive(archive)) return
        val jarFs = JarFileSystem.getInstance()
        val root = jarFs.getJarRootForLocalFile(archive)
            ?: jarFs.refreshAndFindFileByPath(archive.path + JarFileSystem.JAR_SEPARATOR)
            ?: return
        VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(entry: VirtualFile): Boolean {
                if (!entry.isDirectory) {
                    val type = ModelFiles.typeOf(entry)
                    if (type != null) {
                        runCatching { entry.contentsToByteArray() }.getOrNull()?.let { consume(entry.name, it, type, entry) }
                    }
                }
                return true
            }
        })
    }
}
