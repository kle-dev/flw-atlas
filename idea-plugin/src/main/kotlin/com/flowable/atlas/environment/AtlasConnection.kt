package com.flowable.atlas.environment

import com.flowable.atlas.environment.auth.AuthMode

/**
 * An immutable view of one catalog entry, with its environment's name and protection folded in.
 *
 * [AtlasEnvironments] hands these out instead of its own mutable `ConnectionState`, for two reasons.
 * The catalog is application-level and is edited on the EDT while `DesignPullService.pull()` reads it
 * on a pooled thread, so handing out the live record invites a torn read in the middle of a pull. And
 * every consumer would otherwise repeat the same `environments.first { it.id == … }` lookup just to
 * put a name in a status line.
 *
 * There is deliberately no `name`: an environment holds at most one connection per [ConnectionKind],
 * so the kind *is* the name. A per-connection label would be a field with nothing useful to put in
 * it, and every picker would then have to show it instead of the environment the user thinks in.
 */
data class AtlasConnection(
    val id: String,
    val kind: ConnectionKind,
    val baseUrl: String,
    val username: String,
    val authMode: AuthMode,
    val environmentId: String,
    val environmentName: String,
    /** The environment is marked *Protected* — confirm before pulling from or evaluating against it. */
    val requiresConfirmation: Boolean,
    /**
     * Defined by the *project* rather than by this IDE — see [SharedEnvironments]. Pickers say so, and
     * the settings page will not let it be edited: the definition lives in a committed file, so an edit
     * here would be a local change to something the whole team reads.
     */
    val shared: Boolean = false,
) {

    /** `PROD · Design` — what a confirmation dialog and a wide status line name. */
    val displayName: String
        get() = if (environmentName.isBlank()) kind.display else "$environmentName · ${kind.display}"
}

/** An immutable view of one environment, for the editor and the "both kinds" pickers. */
data class AtlasEnvironmentSnapshot(
    val id: String,
    val name: String,
    val requireConfirmation: Boolean,
)
