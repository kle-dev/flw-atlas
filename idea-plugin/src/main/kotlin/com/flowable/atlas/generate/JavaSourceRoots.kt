package com.flowable.atlas.generate

import com.flowable.atlas.project.AtlasProjectRootService
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Where generated Java lands. Shared by "Generate Model Constants" and the "Generate → Data-Object
 * DTOs" dialog so both make the same choice on the same project — including the Remote-Dev /
 * plain-folder case, where a project can expose **no** source roots at all and a modal chooser is the
 * only way out.
 */
object JavaSourceRoots {

    /** Every source root, main-Java ones first — the order the DTO dialog offers them in. */
    fun all(project: Project): List<VirtualFile> {
        val roots = ProjectRootManager.getInstance(project).contentSourceRoots.toList()
        return roots.sortedWith(
            compareBy(
                { !it.path.contains("/src/main/java") },
                { it.path.contains("/test") || it.path.contains("/resources") },
                { it.path },
            ),
        )
    }

    /** Prefer a main Java source root; fall back to any non-test, non-resources source root. */
    fun preferred(project: Project): VirtualFile? = all(project).firstOrNull()

    /** The active project directory on disk, or null when it is not materialized. */
    fun projectDir(project: Project): VirtualFile? =
        AtlasProjectRootService.getInstance(project).activeProjectDir()
            ?.let { LocalFileSystem.getInstance().findFileByNioFile(it) }

    /**
     * A source root for generated code: [preferred], else — when the project exposes no source roots —
     * a folder the user picks in [title], falling back to the active project directory.
     */
    fun preferredOrChosen(project: Project, title: String): VirtualFile? {
        preferred(project)?.let { return it }
        val base = projectDir(project)
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle(title)
            .withDescription("No source root was found — choose where to write the generated code")
        return FileChooser.chooseFile(descriptor, project, base) ?: base
    }

    /** [root] rendered relative to the project directory, for display; absolute path when outside it. */
    fun displayPath(project: Project, root: VirtualFile): String {
        val base = projectDir(project)?.path ?: return root.path
        val relative = runCatching { Path.of(base).relativize(Path.of(root.path)).normalize() }.getOrNull()
        return relative?.takeUnless { it.startsWith("..") || it.toString().isBlank() }
            ?.joinToString("/") ?: root.path
    }
}
