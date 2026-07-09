package com.flowable.atlas.model

import com.flowable.atlas.settings.FlowableAtlasSettings
import com.intellij.openapi.vfs.VirtualFile

/**
 * Shared file classification for Flowable model files — used by both the index and the
 * model-constants auto-refresher.
 */
object ModelFiles {

    // Ported from the original flowable_atlas EXCLUDE_DIRS.
    private val EXCLUDE_DIRS = setOf(
        "target", "build", "node_modules", ".git", ".idea", ".gradle", "dist",
        "out", "bin", ".mvn", "coverage", "__pycache__", ".vscode", "test-classes",
    )

    fun isExcluded(path: String): Boolean = path.split('/').any { it in EXCLUDE_DIRS }

    private fun designIndexingEnabled(): Boolean =
        try { FlowableAtlasSettings.getInstance().indexDesignWorkspace } catch (e: Exception) { false }

    /**
     * Model type of a file, by its deployment-artifact extension (.bpmn, .cmmn, .dmn, .form, .action, ...).
     * When "Index Flowable Design workspace" is enabled, per-model `.json` files under the
     * Design `*-models/` folders are classified by their containing folder too; otherwise only the
     * exported deployment artifacts are indexed (they already carry every published key).
     */
    fun typeOf(file: VirtualFile): ModelType? {
        ModelType.byExtension(file.name)?.let { return it }
        if (designIndexingEnabled() && file.name.endsWith(".json", ignoreCase = true)) {
            return ModelType.byDesignFolder(file.parent?.name)
        }
        return null
    }

    /** True if the path points at a Flowable model artifact we index (excludes build output). */
    fun isModelPath(path: String): Boolean {
        if (isExcluded(path)) return false
        val name = path.substringAfterLast('/')
        if (ModelType.byExtension(name) != null) return true
        if (designIndexingEnabled() && name.endsWith(".json", ignoreCase = true)) {
            val folder = path.substringBeforeLast('/').substringAfterLast('/')
            return ModelType.byDesignFolder(folder) != null
        }
        return false
    }

    /**
     * Filename hint that a file is (likely) a Liquibase changelog. The coverage inspection still
     * confirms by content (`<databaseChangeLog>`), so this only widens/pre-filters the candidates.
     */
    fun isLiquibaseChangelogName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".data.changelog.xml") ||
            n.endsWith("-db-changelog.xml") ||
            (n.endsWith(".xml") && (n.contains("changelog") || n.contains("liquibase")))
    }
}
