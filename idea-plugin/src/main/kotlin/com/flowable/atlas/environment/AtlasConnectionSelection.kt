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
     * The stored pointer meaning *not set, on purpose* — see [selectNone].
     *
     * It starts with [AtlasConnectionIds.SEPARATOR], which the id alphabet (`[a-z0-9-]+`) can never
     * produce, so no environment can ever be named into a collision with it.
     */
    internal val NONE = "${AtlasConnectionIds.SEPARATOR}none"

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
        // A link kind has no pointer, so there is nothing to resolve — and rule 3 below would otherwise
        // report a lone Control URL as "the one this project uses", which is not a thing.
        if (kind.linkOnly) Resolution.NotSet
        else resolve(storedId(project, kind), AtlasCatalog.connections(project, kind))

    fun selected(project: Project, kind: ConnectionKind): AtlasConnection? =
        (resolution(project, kind) as? Resolution.Selected)?.connection

    /** The raw stored pointer, whether or not it still resolves — for the editor and diagnostics.
     *  Always null for a [ConnectionKind.linkOnly] kind, which nothing points at. */
    fun storedId(project: Project, kind: ConnectionKind): String? =
        propertyKey(project, kind)
            ?.let { PropertiesComponent.getInstance(project).getValue(it) }
            ?.takeUnless { it.isBlank() }

    fun select(project: Project, kind: ConnectionKind, connectionId: String) {
        val key = propertyKey(project, kind) ?: return
        if (connectionId == storedId(project, kind)) return
        PropertiesComponent.getInstance(project).setValue(key, connectionId, "")
        AtlasEvents.connectionSelectionChanged(project, kind)
    }

    /**
     * Records *deliberately nothing* — what the picker's **not set** entry means.
     *
     * Not the same as [clear], and the difference is the whole reason this exists. "Nothing stored"
     * already means something: rule 3 in [resolve], which hands the single-environment user their one
     * connection without their ever picking it. So with one environment defined, unsetting the pointer
     * left the fallback answering exactly as before — the picker said *not set*, the panel below it
     * went on showing that environment's workspace and apps, and nothing else in the plugin agreed
     * with the picker. A choice the UI offers has to be a choice the model can hold.
     */
    fun selectNone(project: Project, kind: ConnectionKind) {
        val key = propertyKey(project, kind) ?: return
        if (storedId(project, kind) == NONE) return
        PropertiesComponent.getInstance(project).setValue(key, NONE, "")
        AtlasEvents.connectionSelectionChanged(project, kind)
    }

    /**
     * Forgets the pointer entirely, so the fallback rules decide again — *"I have no opinion"* rather
     * than [selectNone]'s *"none of them"*. What a test teardown wants, and what an import wants
     * before it re-derives a selection; a user's *not set* is the other one.
     */
    fun clear(project: Project, kind: ConnectionKind) {
        val key = propertyKey(project, kind) ?: return
        if (storedId(project, kind) == null) return
        PropertiesComponent.getInstance(project).unsetValue(key)
        AtlasEvents.connectionSelectionChanged(project, kind)
    }

    /** The id used to scope this project's per-connection keys; `""` when nothing resolves. */
    internal fun scopingId(project: Project, kind: ConnectionKind): String =
        selected(project, kind)?.id.orEmpty()

    /**
     * The property holding this project's pointer at [kind], or **null for a kind no project points
     * at** — the Control and Hub addresses are opened in a browser, never "the one this project uses".
     *
     * Exhaustive on purpose. This read `if (kind == DESIGN) DESIGN_PROPERTY else WORK_PROPERTY`, which
     * was right while there were exactly two kinds and silently wrong the moment there were more: a
     * Control link would have been written into — and read back as — the Work pointer, so adding one
     * would have switched the environment the playground evaluates against.
     */
    private fun propertyKey(project: Project, kind: ConnectionKind): String? {
        val base = when (kind) {
            ConnectionKind.DESIGN -> DESIGN_PROPERTY
            ConnectionKind.WORK -> WORK_PROPERTY
            ConnectionKind.CONTROL, ConnectionKind.HUB -> return null
        }
        return AtlasScopedKeys.scoped(base, AtlasProjectRootService.getInstance(project).activeSubProject())
    }

    // ---- the pure decision -------------------------------------------------------------------

    /**
     * Four rules, in order:
     *
     * 0. [NONE] is [Resolution.NotSet] — the user said *not set*, and no rule below may talk them out
     *    of it. Checked first, and before the candidate lookup, so it can never read as *Dangling*.
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
        if (pointer == NONE) return Resolution.NotSet
        if (!pointer.isNullOrBlank()) {
            val hit = candidates.firstOrNull { it.id == pointer }
            return if (hit != null) Resolution.Selected(hit, explicit = true) else Resolution.Dangling(pointer)
        }
        val only = candidates.singleOrNull() ?: return Resolution.NotSet
        return Resolution.Selected(only, explicit = false)
    }
}
