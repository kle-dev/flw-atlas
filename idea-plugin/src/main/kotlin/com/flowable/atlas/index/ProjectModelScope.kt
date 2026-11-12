package com.flowable.atlas.index

import com.flowable.atlas.model.ModelPaths
import com.flowable.atlas.project.AtlasProjectRootService
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * The one definition of "the files Atlas looks at" in the IDE: the active Flowable sub-project's
 * subtree when one is chosen in the Hub, else the project's content roots.
 *
 * The model index scoped itself this way; the Search Everywhere full-text scan, Find Usages into
 * models and the REST-endpoint gutter did not, so in a monorepo the *models* half of a search was
 * narrowed to the chosen app while the *content* half answered from every app in the repository —
 * the same query, two scopes. Every walk goes through here now.
 */
internal object ProjectModelScope {

    /**
     * Visit every non-excluded file in scope. Must be called under a read action. [visit] returns
     * false to stop the walk.
     */
    fun iterateFiles(project: Project, visit: (VirtualFile) -> Boolean) {
        val scopedRoot = scopedRoot(project)
        if (scopedRoot != null) {
            // A direct VFS walk, not a ProjectFileIndex prefix filter, so a sub-project folder outside
            // every content root is still covered.
            VfsUtilCore.iterateChildrenRecursively(
                scopedRoot,
                { vf -> !(vf.isDirectory && vf.name in ModelPaths.EXCLUDE_DIRS) },
                { file -> ProgressManager.checkCanceled(); !project.isDisposed && visit(file) },
            )
        } else {
            ProjectFileIndex.getInstance(project).iterateContent { file ->
                ProgressManager.checkCanceled()
                !project.isDisposed && visit(file)
            }
        }
    }

    /** The sub-project folder narrowing the scope, or null when Atlas works on the whole project. */
    fun scopedRoot(project: Project): VirtualFile? {
        val activeDir = AtlasProjectRootService.getInstance(project).activeProjectDir() ?: return null
        val base = project.basePath?.let { Path.of(it).normalize() } ?: return null
        if (activeDir == base) return null
        return LocalFileSystem.getInstance().findFileByNioFile(activeDir)
    }

    /** Root-relative name of the active scope for a status line — null when it is the whole project. */
    fun label(project: Project): String? {
        val activeDir = AtlasProjectRootService.getInstance(project).activeProjectDir() ?: return null
        val base = project.basePath?.let { Path.of(it).normalize() } ?: return null
        if (activeDir == base) return null
        return runCatching { base.relativize(activeDir).toString() }.getOrNull()?.ifBlank { null }
    }
}
