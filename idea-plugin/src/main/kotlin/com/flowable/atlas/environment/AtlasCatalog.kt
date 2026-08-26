package com.flowable.atlas.environment

import com.intellij.openapi.project.Project

/**
 * The environments a project can actually pick from: the developer's own IDE-wide list
 * ([AtlasEnvironments]) plus the ones this repository ships to its team ([SharedEnvironments]).
 *
 * One function instead of every caller remembering there are two stores. That is the whole point:
 * "which environments exist?" is asked from the Atlas Hub, the playground, the pull action, the
 * protection check and the selection resolver, and a caller that consulted only one of the two would
 * not fail loudly — it would quietly offer a shorter list, or evaluate against an unguarded URL
 * because the *protected* flag lived in the half it did not read.
 *
 * ### Local wins
 *
 * When both define an environment with the same name, the local one is used and the shared one is not
 * shown at all. A developer who made their own `QA` did it after cloning and on purpose — most often to
 * point at their own instance — and a picker offering two entries called `QA` would make every status
 * line in the plugin ambiguous. Shadowing is by **name**, because that is what a team says out loud and
 * the only identity the shared file carries.
 *
 * The merge itself is pure and unit-tested; only the two lookups need a project.
 */
object AtlasCatalog {

    /** Every connection this project can use, of [kind] or all kinds — local first, then shared. */
    fun connections(project: Project, kind: ConnectionKind? = null): List<AtlasConnection> =
        merge(
            AtlasEnvironments.getInstance().connections(kind),
            SharedEnvironments.getInstance(project).connections(kind),
        )

    /** Every environment this project can use. */
    fun environments(project: Project): List<AtlasEnvironmentSnapshot> =
        mergeEnvironments(
            AtlasEnvironments.getInstance().environments(),
            SharedEnvironments.getInstance(project).environments(),
        )

    fun connection(project: Project, id: String): AtlasConnection? =
        if (id.isBlank()) null else connections(project).firstOrNull { it.id == id }

    // ---- the pure decision -------------------------------------------------------------------

    internal fun merge(local: List<AtlasConnection>, shared: List<AtlasConnection>): List<AtlasConnection> {
        val shadowed = local.mapTo(HashSet()) { key(it.environmentName) }
        return local + shared.filterNot { key(it.environmentName) in shadowed }
    }

    internal fun mergeEnvironments(
        local: List<AtlasEnvironmentSnapshot>,
        shared: List<AtlasEnvironmentSnapshot>,
    ): List<AtlasEnvironmentSnapshot> {
        val shadowed = local.mapTo(HashSet()) { key(it.name) }
        return local + shared.filterNot { key(it.name) in shadowed }
    }

    /** Names are compared the way people say them: trimmed, and `qa` is `QA`. */
    private fun key(name: String): String = name.trim().lowercase()
}
