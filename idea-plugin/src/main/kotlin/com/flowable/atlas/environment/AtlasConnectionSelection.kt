package com.flowable.atlas.environment

import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.project.AtlasScopedKeys
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

/**
 * Which connection *this project* uses, per [ConnectionKind] and per active Flowable sub-project.
 *
 * Two pointers, never one: "Pull from Design" and the Expression Playground choose independently, so
 * running against QA while still pulling models from DEV1 is the ordinary case rather than a
 * workaround. There is deliberately no "active environment" that both would follow.
 *
 * Stored **workspace-locally** in [PropertiesComponent], following
 * [AtlasProjectRootService.activeSubProject] and [com.flowable.atlas.design.DesignPullSelection]. It
 * has to be: connection ids belong to the IDE-wide catalog and are per developer, so a committed
 * pointer would name nothing in a colleague's IDE.
 */
object AtlasConnectionSelection {

    private const val DESIGN_PROPERTY = "flowable.atlas.designConnection"
    private const val WORK_PROPERTY = "flowable.atlas.workConnection"

    /**
     * What a feature resolved to. Three cases, not two: the UI must say *"not set"* and *"connection
     * removed"* differently, because the second one means a choice was made and then something else
     * took it away — and the fix is different.
     */
    sealed interface Resolution {

        /** [explicit] is false when this came from a fallback rule and was never stored. */
        data class Selected(val connection: AtlasConnection, val explicit: Boolean) : Resolution

        /** Nothing is stored and no rule could pick unambiguously. */
        data object NotSet : Resolution

        /** A pointer is stored, but no such connection exists any more. */
        data class Dangling(val pointerId: String) : Resolution
    }

    // ---- project-facing ----------------------------------------------------------------------

    fun resolution(project: Project, kind: ConnectionKind): Resolution =
        resolve(storedId(project, kind), AtlasEnvironments.getInstance().connections(kind))

    fun selected(project: Project, kind: ConnectionKind): AtlasConnection? =
        (resolution(project, kind) as? Resolution.Selected)?.connection

    /** The raw stored pointer, whether or not it still resolves — for the editor and diagnostics. */
    fun storedId(project: Project, kind: ConnectionKind): String? =
        PropertiesComponent.getInstance(project).getValue(propertyKey(project, kind))?.takeUnless { it.isBlank() }

    fun select(project: Project, kind: ConnectionKind, connectionId: String) {
        if (connectionId == storedId(project, kind)) return
        PropertiesComponent.getInstance(project).setValue(propertyKey(project, kind), connectionId, "")
        AtlasEvents.connectionSelectionChanged(project, kind)
    }

    fun clear(project: Project, kind: ConnectionKind) {
        if (storedId(project, kind) == null) return
        PropertiesComponent.getInstance(project).unsetValue(propertyKey(project, kind))
        AtlasEvents.connectionSelectionChanged(project, kind)
    }

    /** The id used to scope this project's per-connection keys; `""` when nothing resolves. */
    internal fun scopingId(project: Project, kind: ConnectionKind): String =
        selected(project, kind)?.id.orEmpty()

    private fun propertyKey(project: Project, kind: ConnectionKind): String {
        val base = if (kind == ConnectionKind.DESIGN) DESIGN_PROPERTY else WORK_PROPERTY
        return AtlasScopedKeys.scoped(base, AtlasProjectRootService.getInstance(project).activeSubProject())
    }

    // ---- the pure decision -------------------------------------------------------------------

    /**
     * Three rules, in order:
     *
     * 1. An explicitly stored [pointer] wins.
     * 2. **A stored pointer that no longer resolves is [Resolution.Dangling] — never a substitute.**
     *    The most important line here. `AtlasProjectRootService.activeProjectDir()` may fall back to
     *    the project base because a wider scope is harmless; there is no harmless fallback here.
     *    Falling through to rule 3 after the user deletes DEV would silently promote PROD to "the
     *    server this project pulls from".
     * 3. Nothing stored, and exactly one connection of that kind exists → that one, **unstored**.
     *    This is what makes the single-environment user notice nothing at all: no configuration, no
     *    dropdown to operate. Leaving it unstored is deliberate — the moment a second connection
     *    appears the ambiguity becomes visible instead of being frozen in as a lucky guess.
     */
    fun resolve(pointer: String?, candidates: List<AtlasConnection>): Resolution {
        if (!pointer.isNullOrBlank()) {
            val hit = candidates.firstOrNull { it.id == pointer }
            return if (hit != null) Resolution.Selected(hit, explicit = true) else Resolution.Dangling(pointer)
        }
        val only = candidates.singleOrNull() ?: return Resolution.NotSet
        return Resolution.Selected(only, explicit = false)
    }
}
