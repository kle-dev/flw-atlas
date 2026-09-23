package com.flowable.atlas.model

import com.flowable.atlas.settings.FlowableAtlasSettings
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.ArchiveFileSystem

/**
 * Shared file classification for Flowable model files — used by both the index and the
 * model-constants auto-refresher.
 */
object ModelFiles {

    private val LOG = logger<ModelFiles>()

    fun isExcluded(path: String): Boolean = ModelPaths.isExcluded(path)

    /**
     * [isExcluded] for absolute IDE paths, judged below the project folder they lie in. Tested whole, a
     * project that itself lives under a folder named `build`, `out`, `bin` or `target` (a CI agent's
     * workspace, a dev container) had every file excluded and an empty index. The roots are read once,
     * so a walk asks the returned function per file.
     */
    fun excluder(project: Project): (String) -> Boolean {
        val roots = projectRoots(project)
        return { path -> isExcluded(relativeTo(roots, path) ?: path) }
    }

    /**
     * [path] relative to the outermost of the project folder and its content roots that holds it, or
     * null when it lies outside all of them — a download, another open project, the sandbox IDE's own
     * files. The outermost root, so a content root inside a build folder is still judged as inside it.
     */
    fun projectRelative(project: Project, path: String): String? = relativeTo(projectRoots(project), path)

    private fun projectRoots(project: Project): List<String> {
        if (project.isDisposed) return emptyList()
        return buildList {
            project.basePath?.let(::add)
            ProjectRootManager.getInstance(project).contentRoots.forEach { add(it.path) }
        }
    }

    private fun relativeTo(roots: List<String>, path: String): String? {
        val root = roots.filter { path == it || path.startsWith("$it/") }.minByOrNull { it.length } ?: return null
        return path.substring(root.length).removePrefix("/")
    }

    /** [isModelPath] for an absolute IDE path: false outside the project, and judged below its folder. */
    fun isModelPath(project: Project, path: String): Boolean =
        projectRelative(project, path)?.let { rel -> isModelPath(rel.ifEmpty { path.substringAfterLast('/') }) } == true

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
        } catch (pce: ProcessCanceledException) {
            throw pce                      // a cancelled action is not a failure
        } catch (e: Exception) {
            if (!settingsReadFailed) {
                settingsReadFailed = true
                LOG.debug("Could not read the \"Index Flowable Design workspace\" setting — treating it as off", e)
            }
            false
        }

    /**
     * Model type of a file, by its deployment-artifact extension (.bpmn, .cmmn, .dmn, .form, .action, ...).
     * Per-model `.json` files under the Design `*-models/` folders are classified by their containing
     * folder: inside an archive always (JSON-only types), loose only when "Index Flowable Design
     * workspace" is enabled.
     */
    fun typeOf(file: VirtualFile): ModelType? {
        ModelType.byExtension(file.name)?.let { return it }
        if (!file.name.endsWith(".json", ignoreCase = true)) return null
        val type = ModelType.byDesignFolder(file.parent?.name) ?: return null
        if (designIndexingEnabled()) return type
        // Inside a Design app export a form, page, action or data object exists only as its
        // `form-models/X.json` — the command line reads those whatever the setting says, and the IDE did
        // not: no key, no preview, no outline. Processes, cases and decisions ship their .bpmn/.cmmn/.dmn
        // beside the JSON, which stays the one they are read from.
        return type.takeIf { file.fileSystem is ArchiveFileSystem && it !in XML_BACKED }
    }

    private val XML_BACKED = setOf(ModelType.PROCESS, ModelType.CASE, ModelType.DECISION)

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
