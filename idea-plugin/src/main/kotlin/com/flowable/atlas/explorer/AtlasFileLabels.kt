package com.flowable.atlas.explorer

import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * The file an Atlas file label names. A label is relative to the folder the analysis ran on;
 * `archive!entry` names a model inside a .bar/.zip, which the jar file system mounts read-only. A doubly
 * nested `archive!inner.bar!entry` cannot be mounted, so the outer archive stands in for it. Shared by the
 * explorer page's links and the findings tool window.
 */
internal object AtlasFileLabels {

    fun resolve(root: Path, label: String): VirtualFile? {
        val lfs = LocalFileSystem.getInstance()
        val bang = label.indexOf('!')
        if (bang < 0) return lfs.refreshAndFindFileByNioFile(root.resolve(label))
        val archive = lfs.refreshAndFindFileByNioFile(root.resolve(label.substring(0, bang))) ?: return null
        val entry = label.substring(bang + 1)
        if (entry.contains('!')) return archive
        return JarFileSystem.getInstance().findFileByPath(archive.path + JarFileSystem.JAR_SEPARATOR + entry) ?: archive
    }
}
