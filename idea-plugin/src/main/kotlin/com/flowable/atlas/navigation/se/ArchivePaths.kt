package com.flowable.atlas.navigation.se

import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Presentation of a model file's whereabouts, for models that live *inside* a `.bar`/`.zip`.
 *
 * The path inside an archive is resolved through the VFS (`getVirtualFileForJar` →
 * `getJarRootForLocalFile` → `getRelativePath`) rather than by splitting the raw path on
 * [JarFileSystem.JAR_SEPARATOR]: hand-splitting breaks on nested archives and on an archive whose
 * own name contains `!`.
 */
internal object ArchivePaths {

    /** `app.zip → processes/invoice.bpmn` for an archive entry, else the plain file name. */
    fun displayPath(file: VirtualFile): String {
        val jarFs = file.fileSystem as? JarFileSystem ?: return file.name
        val archive = jarFs.getVirtualFileForJar(file) ?: return file.name
        val root = jarFs.getJarRootForLocalFile(archive) ?: return file.name
        val relative = VfsUtilCore.getRelativePath(file, root) ?: return file.name
        return "${archive.name} → $relative"
    }

}
