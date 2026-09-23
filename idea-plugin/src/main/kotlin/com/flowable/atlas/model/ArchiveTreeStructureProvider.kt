package com.flowable.atlas.model

import com.flowable.atlas.FlowableAtlasBundle
import com.flowable.atlas.index.ArchiveModelScanner
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.TreeStructureProvider
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * A `.zip` / `.bar` in the Project view expands into its entries, so a Design export can be browsed and
 * its models opened (read-only) without unpacking it.
 *
 * The platform expands an archive node only when it is a library: for one inside a content root it
 * mounts the archive and then drops every entry, because an entry is not "in content". This node
 * lists the entries itself, unfiltered.
 */
class ArchiveTreeStructureProvider : TreeStructureProvider, DumbAware {

    override fun modify(
        parent: AbstractTreeNode<*>,
        children: Collection<AbstractTreeNode<*>>,
        settings: ViewSettings,
    ): Collection<AbstractTreeNode<*>> = children.map { child ->
        val file = (child as? PsiFileNode)?.virtualFile
        if (child !is ArchiveNode && file != null && file.isInLocalFileSystem && ArchiveModelScanner.isArchive(file)) {
            ArchiveNode(child.project, child.value, settings)
        } else {
            child
        }
    }

    private class ArchiveNode(project: Project, file: PsiFile, settings: ViewSettings) : PsiFileNode(project, file, settings) {
        override fun getChildrenImpl(): Collection<AbstractTreeNode<*>> {
            val root = virtualFile?.let { JarFileSystem.getInstance().getJarRootForLocalFile(it) } ?: return emptyList()
            val dir = PsiManager.getInstance(project).findDirectory(root) ?: return emptyList()
            return entries(project, dir, settings)
        }
    }

    private class EntryDirectoryNode(project: Project, dir: PsiDirectory, settings: ViewSettings) :
        PsiDirectoryNode(project, dir, settings) {
        override fun getChildrenImpl(): Collection<AbstractTreeNode<*>> = entries(project, value, settings)
    }

    /**
     * An archive inside an archive — a Design export packing one `.bar` per app. The jar file system
     * mounts only archives on disk, so this one cannot open; the node says so instead of looking like an
     * archive that is merely empty.
     */
    private class NestedArchiveNode(project: Project, file: PsiFile, settings: ViewSettings) : PsiFileNode(project, file, settings) {
        override fun updateImpl(data: PresentationData) {
            super.updateImpl(data)
            data.locationString = FlowableAtlasBundle.message("project.view.nested.archive")
        }
    }

    private companion object {
        fun entries(project: Project, dir: PsiDirectory, settings: ViewSettings): List<AbstractTreeNode<*>> =
            dir.subdirectories.map { EntryDirectoryNode(project, it, settings) } +
                dir.files.map {
                    if (ModelPaths.isArchive(it.name)) NestedArchiveNode(project, it, settings) else PsiFileNode(project, it, settings)
                }
    }
}
