package com.flowable.atlas.compare

import com.flowable.atlas.index.ArchiveModelScanner
import com.flowable.atlas.index.ProjectModelScope
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelPaths
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.navigation.se.ArchivePaths
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.project.Project

/**
 * The two sides a comparison can pick from: the model entries packed inside the project's `.bar`/`.zip`
 * archives, and the model files lying loose in the project.
 *
 * ### Why this does not go through the model index
 * [ModelFiles.typeOf] — and therefore every indexed [com.flowable.atlas.index.ModelEntry] — only counts a
 * `.json` file under a `*-models` folder as a model while *Index Flowable Design workspace* is on, and that
 * setting is off by default. Those entries are exactly what an app export consists of, so a comparison
 * built on the index would find the deployment `.form` inside a `-bar.zip` and silently miss the Design
 * JSON next to it. Classification here is [ModelType]'s own, straight from `:core`, with no setting in
 * the path.
 *
 * Reading entry *names* is enough for the match, so nothing here opens an entry's content.
 */
internal object ArchiveEntryCandidates {

    /** One model entry inside one archive, ready to be diffed. */
    data class ArchiveEntry(val archive: VirtualFile, val file: VirtualFile) {
        /** `Demo App.zip → form-models/DEMO-F001.json`. */
        val label: String get() = ArchivePaths.displayPath(file)
    }

    /**
     * Every model entry of every archive in scope, ordered by archive then path — which is also the
     * tie-break inside a [ModelArchiveMatch] rung, because that sort is stable.
     *
     * Runs on a pooled thread: only collecting the archives takes the read lock, and mounting one the
     * VFS has not seen yet needs a synchronous refresh, which must not happen under it.
     */
    fun archiveEntries(project: Project, checkCanceled: () -> Unit): List<ArchiveEntry> {
        val archives = ReadAction.computeBlocking<List<VirtualFile>, RuntimeException> {
            filesInScope(project) { ArchiveModelScanner.isArchive(it) }
        }
        val out = ArrayList<ArchiveEntry>()
        for (archive in archives.sortedBy { it.path.lowercase() }) {
            checkCanceled()
            modelEntriesOf(archive, checkCanceled).sortedBy { it.path.lowercase() }
                .mapTo(out) { ArchiveEntry(archive, it) }
        }
        return out
    }

    /** The model files of the project itself — the other end of the comparison. */
    fun looseModelFiles(project: Project, checkCanceled: () -> Unit): List<VirtualFile> =
        ReadAction.computeBlocking<List<VirtualFile>, RuntimeException> {
            filesInScope(project) { checkCanceled(); isComparable(it) }
        }.sortedBy { it.path.lowercase() }

    /**
     * Whether this file can be one side of a model comparison: a deployment artifact by extension, or a
     * `.json` — the shape a Design export and every generated model file come in, wherever it happens to
     * be parked. An archive itself is not a side.
     */
    fun isComparable(file: VirtualFile): Boolean =
        !file.isDirectory && !ModelPaths.isArchive(file.name) &&
            (ModelType.byExtension(file.name) != null || file.name.endsWith(".json", ignoreCase = true))

    /** Model type of an archive entry: its extension, else the `*-models/` folder holding the `.json`. */
    fun typeOf(entry: VirtualFile): ModelType? =
        ModelType.byExtension(entry.name)
            ?: if (entry.name.endsWith(".json", ignoreCase = true)) ModelType.byDesignFolder(entry.parent?.name) else null

    private fun filesInScope(project: Project, accept: (VirtualFile) -> Boolean): List<VirtualFile> {
        val out = ArrayList<VirtualFile>()
        ProjectModelScope.iterateFiles(project) { file ->
            if (!file.isDirectory && !ModelFiles.isExcluded(file.path) && accept(file)) out.add(file)
            true
        }
        return out
    }

    /** The model entries of one archive, mounted through `jar://…!/`; empty when it cannot be opened. */
    fun modelEntriesOf(archive: VirtualFile, checkCanceled: () -> Unit = {}): List<VirtualFile> {
        val jarFs = JarFileSystem.getInstance()
        val root = jarFs.getJarRootForLocalFile(archive)
            ?: jarFs.refreshAndFindFileByPath(archive.path + JarFileSystem.JAR_SEPARATOR)
            ?: return emptyList()                 // corrupt, encrypted, or not a zip after all
        val out = ArrayList<VirtualFile>()
        VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(entry: VirtualFile): Boolean {
                checkCanceled()
                if (!entry.isDirectory && typeOf(entry) != null) out.add(entry)
                return true
            }
        })
        return out
    }
}
