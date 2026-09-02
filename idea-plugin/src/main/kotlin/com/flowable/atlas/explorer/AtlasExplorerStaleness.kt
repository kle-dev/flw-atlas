package com.flowable.atlas.explorer

import com.flowable.atlas.design.DesignPullService
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Is a generated explorer older than the models it describes?
 *
 * The signal used to be the last Design pull alone, so a `git pull`, an unzipped export or a hand
 * edit never flagged anything, and a team that gets its models through git never saw the hint. The
 * model index already visits every model file and archive; the newest of their modification times is
 * the honest answer, and the pull timestamp stays in as the second source because the pull writes
 * files an instant before the index catches up.
 */
object AtlasExplorerStaleness {

    /** When the models in scope last changed, per what Atlas knows — null before the index exists. */
    fun latestModelChange(project: Project): Long? =
        listOfNotNull(
            DesignPullService.lastPullMillis(project),
            project.service<FlowableModelIndexService>().newestModelMtimeOrNull(),
        ).maxOrNull()

    /** Stale when the newest artifact predates [changedAt]; never stale without artifacts or a change time. */
    fun isStale(artifactMtimes: List<Long>, changedAt: Long?): Boolean =
        changedAt != null && artifactMtimes.isNotEmpty() && artifactMtimes.max() < changedAt
}
