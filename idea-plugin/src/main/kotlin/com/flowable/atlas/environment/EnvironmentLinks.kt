package com.flowable.atlas.environment

/**
 * What "open this environment in the browser" offers: every address in the catalog that a browser can
 * be handed, grouped by the environment it belongs to.
 *
 * Grouping is the whole job, and it has to be done here rather than by rendering
 * [AtlasEnvironments.connections] in the order it comes back. That order is *connection* order — the
 * order things were added across the whole catalog — so a Control URL added to DEV last week lands
 * between QA's and PROD's, and a chooser in that order is unreadable. The environment list is the
 * user's own DEV → QA → UAT → PROD pipeline, and within one environment the kinds keep their declared
 * order, so the same environment always looks the same.
 *
 * All four kinds are offered, not just the two link-only ones: a Design server's base URL *is* the
 * Design UI and a Work base URL *is* the app, so leaving them out would mean keeping bookmarks for
 * exactly the two addresses Atlas already knows best.
 *
 * A connection with no URL typed yet is dropped — a menu entry that opens nothing is worse than one
 * that is not there. Pure and unit-tested: no IDE, no services.
 */
object EnvironmentLinks {

    /** One environment and everything of it that can be opened; never empty by construction. */
    data class Group(val environment: AtlasEnvironmentSnapshot, val links: List<AtlasConnection>)

    fun grouped(
        environments: List<AtlasEnvironmentSnapshot>,
        connections: List<AtlasConnection>,
    ): List<Group> {
        val byEnvironment = connections
            .filter { it.baseUrl.isNotBlank() }
            .groupBy { it.environmentId }
        return environments.mapNotNull { environment ->
            val links = byEnvironment[environment.id]
                ?.sortedBy { it.kind.ordinal }
                ?: return@mapNotNull null
            Group(environment, links)
        }
    }

    /** True when the chooser would have nothing in it — what a control's enabled state is keyed on. */
    fun isEmpty(
        environments: List<AtlasEnvironmentSnapshot>,
        connections: List<AtlasConnection>,
    ): Boolean = grouped(environments, connections).isEmpty()
}
