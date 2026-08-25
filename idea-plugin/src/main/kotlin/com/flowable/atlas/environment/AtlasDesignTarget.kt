package com.flowable.atlas.environment

import com.flowable.atlas.design.DesignPullSelection
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.project.Project

/**
 * Everything a Design pull needs, resolved **once**: which server, which workspace, which apps, which
 * folder.
 *
 * This replaces `FlowableAtlasProjectSettings.hasDesignServer()`/`isDesignPullReady()` and the habit
 * of re-reading `settings.designBaseUrl` at each point of use — `DesignPullService.pull()` read it
 * four times and `AtlasHubPanel` twice more, and every one of those reads was an independent chance to
 * disagree with the others. A non-null [connection] is the point of the type: "is a server
 * configured?" stops being a predicate anyone can forget to call, because there is no way to hold a
 * target without one.
 *
 * There is one selection, not a default plus an override: the Hub edits the project's setting
 * directly, so what the panel shows is what a pull does.
 */
data class AtlasDesignTarget(
    val connection: AtlasConnection,
    val workspaceKey: String,
    val appKeys: List<String>,
    val targetFolder: String,
) {

    /** True when a pull can run unattended — a server alone is not enough. */
    fun isPullReady(): Boolean = workspaceKey.isNotBlank() && appKeys.isNotEmpty()

    companion object {

        /** What a pull would fetch right now, or null when no Design connection resolves. */
        fun resolve(project: Project): AtlasDesignTarget? = build(project)

        /**
         * What this project pulls from [connection]'s environment. One lookup, one home — every surface
         * that used to assemble this from two settings fields now asks here, so they cannot disagree
         * about which environment they meant.
         */
        fun selection(project: Project, connection: AtlasConnection): DesignPullSelection {
            val target = FlowableAtlasProjectSettings.getInstance(project)
                .pullTargetOrNull(connection.environmentName) ?: return DesignPullSelection.EMPTY
            return DesignPullSelection(target.workspaceKey, target.appKeys.toList())
        }

        private fun build(project: Project): AtlasDesignTarget? {
            val connection = AtlasConnectionSelection.selected(project, ConnectionKind.DESIGN) ?: return null
            val settings = FlowableAtlasProjectSettings.getInstance(project)
            val selection = selection(project, connection)
            return AtlasDesignTarget(
                connection = connection,
                workspaceKey = selection.workspaceKey,
                appKeys = selection.appKeys,
                targetFolder = settings.designTargetFolder
                    .ifBlank { FlowableAtlasProjectSettings.DEFAULT_DESIGN_TARGET_FOLDER },
            )
        }
    }
}
