package com.flowable.atlas.project

import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.parsing.ProjectDetection
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves *which* Flowable project Atlas operates on when the open IntelliJ project's root holds
 * several of them (a monorepo / multi-module layout). The single "active sub-project" (a
 * project-root-relative path; `""` = the whole project = the historical single-project behavior) is
 * the anchor for generation, the model index and the per-sub-project settings.
 *
 * Everything that used to read [Project.getBasePath] directly should call [activeProjectDir] instead.
 * The selection itself is stored **workspace-local** in [PropertiesComponent] (it lands in the
 * non-shared `workspace.xml`, not the VCS-shared `.idea/flowable-atlas.xml`) — one developer switching
 * sub-projects must not flip the choice for the whole team, mirroring
 * [com.flowable.atlas.design.DesignPullService]'s last-pull timestamp.
 */
@Service(Service.Level.PROJECT)
class AtlasProjectRootService(private val project: Project) {

    @Volatile
    private var detectedCache: List<ProjectDetection.SubProject>? = null

    /** The active sub-project's root-relative path; `""` when the whole project is used. */
    fun activeSubProject(): String =
        PropertiesComponent.getInstance(project)
            .getValue(FlowableAtlasProjectSettings.ACTIVE_SUBPROJECT_PROPERTY, "")

    /**
     * The directory Atlas analyses: the base path resolved against the active sub-project, or the base
     * path itself when none is selected. `null` when the project has no base path on disk. A stale
     * selection (folder renamed/deleted, or one that escapes the base path) falls back to the base
     * path, so a bad pointer never breaks generation.
     */
    fun activeProjectDir(): Path? {
        val base = project.basePath?.let { Path.of(it).normalize() } ?: return null
        val key = activeSubProject()
        if (key.isBlank()) return base
        return runCatching {
            val resolved = base.resolve(key).normalize()
            if (resolved != base && resolved.startsWith(base) && Files.isDirectory(resolved)) resolved else base
        }.getOrDefault(base)
    }

    /** Persist the selection (workspace-local), invalidate the index and notify listeners. */
    fun setActiveSubProject(relPath: String?) {
        val key = relPath?.trim()?.trim('/').orEmpty()
        if (key == activeSubProject()) return
        PropertiesComponent.getInstance(project)
            .setValue(FlowableAtlasProjectSettings.ACTIVE_SUBPROJECT_PROPERTY, key, "")
        if (project.isDisposed) return
        project.service<FlowableModelIndexService>().invalidate()
        project.messageBus.syncPublisher(AtlasEvents.TOPIC).activeSubProjectChanged()
    }

    /** The cached detection result, or `null` if it has not been computed — never scans here. */
    fun detectedOrNull(): List<ProjectDetection.SubProject>? = detectedCache

    /** Detect sub-projects on a pooled thread (never the EDT), cache it, then run [onDone] with it. */
    fun detectAsync(onDone: (List<ProjectDetection.SubProject>) -> Unit) {
        val base = project.basePath ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val detected = runCatching { ProjectDetection.detect(File(base)) }.getOrDefault(emptyList())
            detectedCache = detected
            onDone(detected)
        }
    }

    companion object {
        fun getInstance(project: Project): AtlasProjectRootService = project.service()
    }
}
