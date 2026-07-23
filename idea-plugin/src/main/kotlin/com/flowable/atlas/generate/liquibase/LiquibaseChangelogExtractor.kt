package com.flowable.atlas.generate.liquibase

import com.flowable.atlas.liquibase.LiquibaseChangelog
import com.flowable.atlas.model.ModelPaths
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor

/**
 * Reads the Liquibase changelogs a Flowable Design app export bundles at
 * `com/flowable/app/design/<App>/liquibase-models/<name>.data.changelog.xml`. The archive is mounted via
 * the `jar://…!/` protocol (mirroring [com.flowable.atlas.index.ArchiveModelScanner]) so each entry
 * is read straight from the zip without unpacking — the changelog is emitted verbatim by the caller.
 */
object LiquibaseChangelogExtractor {

    /** One bundled changelog: its [key] (from the filename) and the raw [xml] read from the zip. */
    data class Extracted(val key: String, val fileName: String, val xml: String)

    /** True for a `.zip`/`.bar` archive that could hold a Design app export. */
    fun isArchive(file: VirtualFile): Boolean = !file.isDirectory && ModelPaths.isArchive(file.name)

    /**
     * Every bundled changelog inside [archive] (a `liquibase-models/` entry ending in
     * `.changelog.xml`), read verbatim. Empty when [archive] is not a readable app export.
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
                if (!entry.isDirectory && isBundledChangelog(entry)) {
                    val xml = runCatching { String(entry.contentsToByteArray(), Charsets.UTF_8) }.getOrNull()
                    if (xml != null) {
                        val key = LiquibaseChangelog.changelogKey(entry.name)
                        out.putIfAbsent(key, Extracted(key, entry.name, xml))
                    }
                }
                return true
            }
        })
        return out.values.toList()
    }

    /** A `*.changelog.xml` under a `liquibase-models/` folder — the Design-export bundling convention. */
    private fun isBundledChangelog(entry: VirtualFile): Boolean =
        entry.name.endsWith(".changelog.xml", ignoreCase = true) &&
            entry.parent?.name?.equals("liquibase-models", ignoreCase = true) == true
}
