package com.flowable.atlas.hub

import com.flowable.atlas.environment.AtlasConnectionSelection.Resolution
import com.flowable.atlas.environment.ConnectionKind

/**
 * The one thing the Hub asks the reader to act on, if anything.
 *
 * The panel used to scatter its warnings: an amber note beside the project picker, a stale row inside
 * the Explorer section, an archive count folded into the index text, a red "was removed" beside a combo.
 * Each one appeared and disappeared in its own place, so the panel's height and shape changed with its
 * mood. The header has exactly one attention line now, and this decides what it says — in the order of
 * what goes wrong first: the next click hitting the wrong server, then the whole panel being about the
 * wrong project, then data Atlas could not see, then artifact drift.
 *
 * Pure so the priority order is a unit test, not a screenshot.
 */
internal sealed interface HubAttention {
    /** The project points at an environment that no longer exists. Never silently falls back to another. */
    data class RemovedEnvironment(val kind: ConnectionKind) : HubAttention

    /** Several Flowable projects, and nobody has said which one Atlas is about. */
    data class ChooseProject(val count: Int) : HubAttention

    /** The index could not be built, so the whole panel is about nothing — said, with a Rebuild. */
    data class IndexFailed(val reason: String) : HubAttention

    /** Archives the index could not open — an unreadable .bar used to look like an empty project. */
    data class UnreadableArchives(val names: List<String>) : HubAttention

    /** A model in scope is newer than the newest generated explorer page — [changed] names them, by key. */
    data class StaleExplorer(val changed: List<String>) : HubAttention

    companion object {
        fun of(s: HubSnapshot): HubAttention? = when {
            s.designResolution is Resolution.Dangling -> RemovedEnvironment(ConnectionKind.DESIGN)
            s.workResolution is Resolution.Dangling -> RemovedEnvironment(ConnectionKind.WORK)
            s.projectsAwaitingChoice >= 2 -> ChooseProject(s.projectsAwaitingChoice)
            s.indexFailure != null -> IndexFailed(s.indexFailure)
            s.skippedArchives.isNotEmpty() -> UnreadableArchives(s.skippedArchives)
            s.explorerStale -> StaleExplorer(s.changedModels)
            else -> null
        }
    }
}
