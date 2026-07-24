package com.flowable.atlas.generate.liquibase

import com.flowable.atlas.liquibase.LiquibaseChangelog
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelPaths
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor

/**
 * Reads the Liquibase changelogs a Flowable Design app export ships. Design bundles them by *file name*
 * (`<key>.data.changelog.xml`, possibly `liquibase-<key>.…`); the containing folder is not part of the
 * convention — a freshly exported app ZIP keeps them flat at the root, a deployed business archive nests
 * them. Detection therefore keys off the file name (mirroring [com.flowable.atlas.model.ModelFiles] /
 * [com.flowable.atlas.index.ArchiveModelScanner], which also classify entries folder-agnostically) and
 * confirms by content (`<databaseChangeLog>`) so the wider name match can never let a non-Liquibase XML
 * through.
 *
 * Archives are mounted via the `jar://…!/` protocol so each entry is read straight from the ZIP without
 * unpacking; an already-unpacked export contributes its loose `*.data.changelog.xml` files directly. The
 * changelog XML is emitted verbatim by the caller.
 */
object LiquibaseChangelogExtractor {

    /** One bundled changelog: its [key] (from the filename) and the raw [xml] read from the archive/file. */
    data class Extracted(val key: String, val fileName: String, val xml: String)

    /** True for a `.zip`/`.bar` archive that could hold a Design app export. */
    fun isArchive(file: VirtualFile): Boolean = !file.isDirectory && ModelPaths.isArchive(file.name)

    /**
     * A loose (already-unpacked) per-data-object Design changelog in the project tree. Deliberately the
     * narrow `.data.changelog.xml` suffix — not the wider [ModelFiles.isLiquibaseChangelogName] — so the
     * project's own (or just-generated) master `*-db-changelog.xml` is never mistaken for an app export.
     */
    fun isLooseChangelog(file: VirtualFile): Boolean =
        !file.isDirectory && file.name.endsWith(".data.changelog.xml", ignoreCase = true)

    /**
     * Every bundled changelog inside [archive] — an entry whose name looks like a Liquibase changelog and
     * whose body confirms it — read verbatim. Empty when [archive] is not a readable app export.
     */
    fun extract(archive: VirtualFile): List<Extracted> {
        if (!isArchive(archive)) return emptyList()
        val jarFs = JarFileSystem.getInstance()
        val root = jarFs.getJarRootForLocalFile(archive)
            ?: jarFs.refreshAndFindFileByPath(archive.path + JarFileSystem.JAR_SEPARATOR)
            ?: return emptyList()
        val out = LinkedHashMap<String, Extracted>()
        VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(entry: VirtualFile): Boolean {
                if (!entry.isDirectory && ModelFiles.isLiquibaseChangelogName(entry.name)) {
                    readChangelog(entry)?.let { out.putIfAbsent(it.key, it) }
                }
                return true
            }
        })
        return out.values.toList()
    }

    /** Read a loose changelog [file] from the project tree, or null when it is unreadable / not a changelog. */
    fun readLoose(file: VirtualFile): Extracted? = readChangelog(file)

    /** Read [entry] verbatim, keeping it only when its body really is a changelog; null otherwise. */
    private fun readChangelog(entry: VirtualFile): Extracted? {
        val xml = runCatching { String(entry.contentsToByteArray(), Charsets.UTF_8) }.getOrNull() ?: return null
        if (!looksLikeChangelog(xml)) return null
        return Extracted(LiquibaseChangelog.changelogKey(entry.name), entry.name, xml)
    }

    /** Body confirmation for the file-name match — every real Liquibase changelog has this root element. */
    private fun looksLikeChangelog(xml: String): Boolean = xml.contains("<databaseChangeLog", ignoreCase = true)
}
