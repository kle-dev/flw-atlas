package com.flowable.atlas.model

import com.flowable.atlas.settings.FlowableAtlasSettings
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile

/**
 * Shared file classification for Flowable model files — used by both the index and the
 * model-constants auto-refresher.
 */
object ModelFiles {

    private val LOG = logger<ModelFiles>()

    fun isExcluded(path: String): Boolean = ModelPaths.isExcluded(path)

    /** Set once the settings read has failed, so the hot path below logs the cause once, not per file. */
    @Volatile private var settingsReadFailed = false

    /**
     * Whether Design-workspace `.json` models count as models. Reached from [typeOf] for every file the
     * index classifies, so it must stay cheap and must never throw — the application service is absent
     * in a light test fixture and during shutdown, and one unavailable setting may not abort indexing.
     *
     * Falling back to `false` silently used to make "setting is off" and "setting could not be read"
     * look identical, which is the difference between an empty Design workspace by choice and by
     * accident. Logged once (not per file) so the distinction survives without flooding idea.log.
     */
    private fun designIndexingEnabled(): Boolean =
        try {
            FlowableAtlasSettings.getInstance().indexDesignWorkspace
        } catch (e: Exception) {
            if (!settingsReadFailed) {
                settingsReadFailed = true
                LOG.debug("Could not read the \"Index Flowable Design workspace\" setting — treating it as off", e)
            }
            false
        }

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
        if (ModelPaths.isArchive(name)) return true   // .bar/.zip archives are indexed (mounted) content too
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
