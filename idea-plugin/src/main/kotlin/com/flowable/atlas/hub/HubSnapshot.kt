package com.flowable.atlas.hub

import com.flowable.atlas.design.DesignPullSelection
import com.flowable.atlas.design.DesignPullService
import com.flowable.atlas.environment.AtlasCatalog
import com.flowable.atlas.environment.AtlasConnection
import com.flowable.atlas.environment.AtlasConnectionSelection
import com.flowable.atlas.environment.AtlasConnectionSelection.Resolution
import com.flowable.atlas.environment.AtlasDesignTarget
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.explorer.AtlasBrowser
import com.flowable.atlas.explorer.AtlasExplorerFiles
import com.flowable.atlas.explorer.AtlasExplorerStaleness
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.index.ProjectModelScope
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

/** A generated explorer page: where it is, project-relative, and when it was written. */
internal data class ExplorerArtifact(val path: Path, val relative: String, val modified: Long)

/**
 * Everything the Atlas Hub shows, read in one pass on a pooled thread and applied on the EDT.
 *
 * Data, not text: the old snapshot carried two ready-made HTML strings, which meant the header could not
 * decide what to say without re-parsing what it had been given. The header and the attention line derive
 * their words from these fields, and a test can build one without Swing.
 *
 * [gather] is network-free and never blocks on an index build — it reads
 * [FlowableModelIndexService.cachedOrNull] and asks for a build when there is none, so "scanning…" resolves
 * itself on the next `modelIndexUpdated`.
 */
internal data class HubSnapshot(
    /** Detected Flowable sub-projects, root-relative — the picker's choices beside "whole project". */
    val subProjects: List<String>,
    /** The one in use; `""` is the whole repository. */
    val activeSubProject: String,
    /** How many projects were detected while nobody has said which — 0 whenever the question does not arise. */
    val projectsAwaitingChoice: Int,
    /** Null while the index is being built. */
    val modelCount: Int?,
    val typeCounts: List<Pair<ModelType, Int>>,
    /** Where the index looked when that is narrower than the repository. */
    val scopeLabel: String?,
    val builtAtMillis: Long,
    val skippedArchives: List<String>,
    val artifacts: List<ExplorerArtifact>,
    val explorerStale: Boolean,
    val browserAvailable: Boolean,
    val designResolution: Resolution,
    val workResolution: Resolution,
    /** False only before anything at all has been defined — the one state worth its own wording. */
    val hasAnyEnvironment: Boolean,
    /** What a pull would fetch right now. */
    val pullSelection: DesignPullSelection,
    val lastPullMillis: Long?,
    /** Where the explorer search looked — what the empty state has to name to be believable. */
    val searchedIn: String,
) {
    val designConnection: AtlasConnection?
        get() = (designResolution as? Resolution.Selected)?.connection

    /** A Design connection resolved — enough to show (and use) the workspace and app pickers. */
    val designServerSet: Boolean
        get() = designConnection != null

    companion object {
        /** [onDetected] runs when a sub-project detection that was not ready yet finishes. */
        fun gather(project: Project, onDetected: () -> Unit): HubSnapshot {
            val settings = FlowableAtlasProjectSettings.getInstance(project)
            val rootService = AtlasProjectRootService.getInstance(project)
            val base = rootService.activeProjectDir()

            val active = rootService.activeSubProject()
            val detected = rootService.detectedOrNull()
            if (detected == null) rootService.detectAsync { onDetected() }
            val subCount = detected?.size ?: 0
            // The one thing the picker cannot say by itself: that a choice is *outstanding*. Several
            // projects and nobody has picked means Atlas is indexing the whole repository by default,
            // which is a decision the user never made.
            val awaiting = if (subCount >= 2 && !rootService.hasChosenProject() && active.isBlank()) subCount else 0

            val indexService = project.service<FlowableModelIndexService>()
            val index = indexService.cachedOrNull()
            if (index == null) indexService.ensureBuilding()
            val typeCounts = index?.let { idx ->
                val byType = idx.allDistinct().groupBy { it.type }
                ModelType.entries.mapNotNull { t -> byType[t]?.let { t to it.size } }
            }.orEmpty()

            val artifacts = base?.let { b ->
                AtlasExplorerFiles.find(b, settings.atlasOutputDir).map { p ->
                    val rel = runCatching { b.relativize(p).parent?.toString() ?: "" }.getOrDefault("")
                    val modified = runCatching { Files.getLastModifiedTime(p).toMillis() }.getOrDefault(0L)
                    ExplorerArtifact(p, rel, modified)
                }
            }.orEmpty()

            val designResolution = AtlasConnectionSelection.resolution(project, ConnectionKind.DESIGN)
            val workResolution = AtlasConnectionSelection.resolution(project, ConnectionKind.WORK)
            val designConnection = (designResolution as? Resolution.Selected)?.connection
            val pullSelection = designConnection?.let { AtlasDesignTarget.selection(project, it) }
                ?: DesignPullSelection.EMPTY

            return HubSnapshot(
                subProjects = detected?.map { it.relPath }.orEmpty(),
                activeSubProject = active,
                projectsAwaitingChoice = awaiting,
                modelCount = index?.distinctCount(),
                typeCounts = typeCounts,
                scopeLabel = ProjectModelScope.label(project),
                builtAtMillis = index?.builtAtMillis ?: 0L,
                skippedArchives = index?.skippedArchives?.toList().orEmpty(),
                artifacts = artifacts,
                // Stale when a model in scope is newer than the newest generated page.
                explorerStale = AtlasExplorerStaleness.isStale(
                    artifacts.map { it.modified }, AtlasExplorerStaleness.latestModelChange(project),
                ),
                browserAvailable = AtlasBrowser.canOpenFiles(),
                designResolution = designResolution,
                workResolution = workResolution,
                hasAnyEnvironment = AtlasCatalog.environments(project).isNotEmpty(),
                pullSelection = pullSelection,
                lastPullMillis = DesignPullService.lastPullMillis(project),
                searchedIn = listOfNotNull(active.ifBlank { null }, settings.atlasOutputDir).joinToString("/") + "/",
            )
        }
    }
}
