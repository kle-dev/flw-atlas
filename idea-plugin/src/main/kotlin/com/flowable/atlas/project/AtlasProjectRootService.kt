package com.flowable.atlas.project

import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.parsing.ProjectDetection
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
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
class AtlasProjectRootService(private val project: Project) : Disposable {

    private val LOG = logger<AtlasProjectRootService>()

    @Volatile
    private var detectedCache: List<ProjectDetection.SubProject>? = null

    init {
        // The detection is a walk over the project's folders and build files; a folder added to a monorepo
        // used to appear in the picker after a restart only. A structural change — a folder or a build
        // file created, deleted, moved or renamed — drops the cache, and the Hub's next refresh re-detects.
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    if (events.any { changesStructure(it) }) detectedCache = null
                }
            },
        )
    }

    private fun changesStructure(e: VFileEvent): Boolean {
        val name = e.path.substringAfterLast('/')
        val marker = name in BUILD_FILES
        return when (e) {
            is VFileCreateEvent -> e.isDirectory || marker
            is VFileDeleteEvent -> e.file.isDirectory || marker
            is VFileMoveEvent -> e.file.isDirectory || marker
            is VFilePropertyChangeEvent -> e.propertyName == VirtualFile.PROP_NAME && (e.file.isDirectory || marker)
            else -> false
        }
    }

    override fun dispose() {}

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
        // Debug: falling back to the project base is a correct, safe answer (it just widens the scope),
        // and an unparsable stored sub-project key is user data, not a defect.
        return runCatching {
            val resolved = base.resolve(key).normalize()
            if (resolved != base && resolved.startsWith(base) && Files.isDirectory(resolved)) resolved else base
        }.onFailure { LOG.debug("Stored sub-project key '$key' does not resolve under $base", it) }
            .getOrDefault(base)
    }

    /**
     * True once the user has explicitly picked a project — including deliberately choosing the whole
     * project. Lets the UI stop nagging "choose one" after a choice, since `""` alone can't tell an
     * explicit "whole project" apart from the never-decided default.
     */
    fun hasChosenProject(): Boolean =
        PropertiesComponent.getInstance(project).getBoolean(PROJECT_CHOSEN_PROPERTY, false)

    /** Persist the selection (workspace-local), invalidate the index and notify listeners. */
    fun setActiveSubProject(relPath: String?) {
        val key = relPath?.trim()?.trim('/').orEmpty()
        val changed = key != activeSubProject()
        val pc = PropertiesComponent.getInstance(project)
        pc.setValue(FlowableAtlasProjectSettings.ACTIVE_SUBPROJECT_PROPERTY, key, "")
        pc.setValue(PROJECT_CHOSEN_PROPERTY, true, false)   // even choosing "whole project" counts
        if (project.isDisposed) return
        if (changed) project.service<FlowableModelIndexService>().invalidate()
        // Always notify, even when the effective scope is unchanged, so the Hub drops the prompt.
        project.messageBus.syncPublisher(AtlasEvents.TOPIC).activeSubProjectChanged()
    }

    /** The cached detection result, or `null` if it has not been computed — never scans here. */
    fun detectedOrNull(): List<ProjectDetection.SubProject>? = detectedCache

    /** Detect sub-projects on a pooled thread (never the EDT), cache it, then run [onDone] with it. */
    fun detectAsync(onDone: (List<ProjectDetection.SubProject>) -> Unit) {
        val base = project.basePath ?: return
        // One walk at a time: the Hub asks on every refresh until a result exists, and a burst of
        // events (settings applied, environments changed, index updated) used to queue a full
        // detection per event. The running walk's onDone refreshes the Hub for all of them.
        if (!detecting.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (project.isDisposed) return@executeOnPooledThread
                // Warn: an empty result disables the whole sub-project switcher, so "detection crashed"
                // must not look like "this is a single-project repo".
                val detected = runCatching { ProjectDetection.detect(File(base)) }
                    .onFailure { LOG.warn("Flowable sub-project detection failed under $base", it) }
                    .getOrDefault(emptyList())
                detectedCache = detected
                onDone(detected)
            } finally {
                detecting.set(false)
            }
        }
    }

    private val detecting = java.util.concurrent.atomic.AtomicBoolean()

    companion object {
        /** The files whose presence makes a folder a project to [ProjectDetection]. */
        private val BUILD_FILES = setOf("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")

        /** Whether the user has made an explicit project choice, in [PropertiesComponent] (workspace-local). */
        const val PROJECT_CHOSEN_PROPERTY = "flowable.atlas.projectChosen"

        fun getInstance(project: Project): AtlasProjectRootService = project.service()
    }
}
