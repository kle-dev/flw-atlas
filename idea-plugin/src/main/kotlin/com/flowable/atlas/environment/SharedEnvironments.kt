package com.flowable.atlas.environment

import com.flowable.atlas.environment.auth.AuthMode
import com.flowable.atlas.events.AtlasEvents
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * The environments **this repository** defines for whoever clones it — `.idea/flowable-environments.xml`,
 * committed, so a new colleague opens the project and DEV/QA/PROD are already in every picker.
 *
 * ### What is in the file, and what can never be
 *
 * A name, whether the stage is protected, and one URL per [ConnectionKind] (plus Design's auth *mode*,
 * which is a fact about the server, not a secret). That is the whole schema — and the absence of a
 * username or password field is the design, not an omission someone still has to remember: there is
 * nowhere to put a credential, so no future edit can leak one into a shared file by accident.
 *
 * Credentials stay exactly where they were: the IDE password safe, keyed by base URL. Each developer
 * signs in as themselves, which is what you want anyway — a team-shared login is one audit trail with
 * everyone's name missing from it.
 *
 * ### Why a second store rather than a flag on [AtlasEnvironments]
 *
 * The two answer different questions. [AtlasEnvironments] is *"the servers I work with"*, IDE-wide and
 * per developer, and it is where credentials, usernames and personal stages live. This is *"the servers
 * this project is about"*, versioned with the code that talks to them — so a URL that moves is a commit
 * that reaches everyone, instead of six people editing six settings dialogs. Merging them into one
 * store would mean one file holding both roaming personal data and VCS-shared team data, and every
 * write would have to decide which half it was touching.
 *
 * ### Identity is the name
 *
 * There are no ids in the file. An id in [AtlasEnvironments] is a per-developer artefact, and
 * committing one would be committing a local detail; the *name* is what a team says out loud ("it is
 * on QA"), so the name is the identity, and the ids consumers need are derived from it and prefixed —
 * see [idOf]. A local environment of the same name **shadows** the shared one entirely
 * ([AtlasCatalog]): your own definition of QA wins over the repository's, because you made it later
 * and on purpose.
 */
@Service(Service.Level.PROJECT)
@State(name = "FlowableAtlasSharedEnvironments", storages = [Storage("flowable-environments.xml")])
class SharedEnvironments : PersistentStateComponent<SharedEnvironments.State> {

    /** One shared connection: a kind, a URL, and — for Design — how the server wants to be talked to. */
    class ConnectionState() {

        /** By name with a blank default, for the reason spelled out on [AtlasEnvironments.ConnectionState]. */
        var kind: String = ""
        var baseUrl: String = ""
        var authMode: AuthMode = AuthMode.BASIC

        constructor(kind: ConnectionKind, baseUrl: String, authMode: AuthMode = AuthMode.BASIC) : this() {
            this.kind = kind.name
            this.baseUrl = baseUrl
            this.authMode = authMode
        }

        internal fun resolvedKind(): ConnectionKind? = ConnectionKind.byName(kind)
    }

    /** One shared environment. Note what it has no field for: a username, and anything secret. */
    class EnvironmentState() {
        var name: String = ""
        var requireConfirmation: Boolean = false
        var connections: MutableList<ConnectionState> = mutableListOf()

        constructor(name: String, requireConfirmation: Boolean, connections: List<ConnectionState>) : this() {
            this.name = name
            this.requireConfirmation = requireConfirmation
            this.connections = connections.toMutableList()
        }
    }

    class State {
        var environments: MutableList<EnvironmentState> = mutableListOf()
    }

    private var state = State()
    private val lock = Any()

    // ---- PersistentStateComponent -----------------------------------------------------------

    override fun getState(): State = synchronized(lock) { sanitize(state) }

    /**
     * Also fires [AtlasEvents.environmentsChanged]: a `git pull` that changes this file is reloaded by
     * the platform behind everyone's back, and a Hub still offering the URL that moved is exactly the
     * kind of quietly-wrong state the environments feature exists to remove. The listener contract only
     * allows *scheduling* work, which is all any subscriber does.
     */
    override fun loadState(newState: State) {
        synchronized(lock) { state = sanitize(newState) }
        AtlasEvents.environmentsChanged()
    }

    // ---- reads: snapshots only --------------------------------------------------------------

    fun isEmpty(): Boolean = synchronized(lock) { state.environments.isEmpty() }

    fun environments(): List<AtlasEnvironmentSnapshot> = synchronized(lock) {
        state.environments.map { AtlasEnvironmentSnapshot(idOf(it.name), it.name, it.requireConfirmation) }
    }

    /** Every shared connection as the rest of the plugin sees one — with no username, and [AtlasConnection.shared]. */
    fun connections(kind: ConnectionKind? = null): List<AtlasConnection> = synchronized(lock) {
        state.environments.flatMap { environment ->
            val environmentId = idOf(environment.name)
            environment.connections.mapNotNull { connection ->
                val resolved = connection.resolvedKind() ?: return@mapNotNull null
                if (kind != null && resolved != kind) return@mapNotNull null
                AtlasConnection(
                    id = connectionIdOf(environment.name, resolved),
                    kind = resolved,
                    baseUrl = connection.baseUrl,
                    // Blank on purpose. Both credential stores fall back to the account name on their
                    // PasswordSafe record, so each developer's own username round-trips through the
                    // keychain without the file ever holding one.
                    username = "",
                    authMode = connection.authMode,
                    environmentId = environmentId,
                    environmentName = environment.name,
                    requiresConfirmation = environment.requireConfirmation,
                    shared = true,
                )
            }
        }
    }

    // ---- mutations ---------------------------------------------------------------------------

    /**
     * Writes [name] into the file, replacing whatever was there under that name. Takes
     * [AtlasConnection]s because the caller has them — and takes **only** their kind, URL and auth
     * mode, which is the second place this refuses to carry a username.
     */
    fun share(name: String, requireConfirmation: Boolean, connections: List<AtlasConnection>) = mutate {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return@mutate
        val entry = EnvironmentState(
            trimmed,
            requireConfirmation,
            connections
                .filter { it.baseUrl.isNotBlank() }
                .map { ConnectionState(it.kind, BaseUrls.normalize(it.kind, it.baseUrl), it.authMode) },
        )
        val at = state.environments.indexOfFirst { it.name.equals(trimmed, ignoreCase = true) }
        if (at >= 0) state.environments[at] = entry else state.environments.add(entry)
    }

    /** Takes [name] out of the file. The developer's own copy, if they made one, is untouched. */
    fun unshare(name: String) = mutate {
        state.environments.removeAll { it.name.equals(name.trim(), ignoreCase = true) }
    }

    /** Releases the lock before publishing — see the note on [AtlasEnvironments.mutate]. */
    private inline fun <T> mutate(block: () -> T): T {
        val result = synchronized(lock) { block().also { state = sanitize(state) } }
        AtlasEvents.environmentsChanged()
        return result
    }

    companion object {

        fun getInstance(project: Project): SharedEnvironments = project.service()

        /**
         * The id consumers use for a shared environment, derived from its name.
         *
         * Prefixed with `shared.` — and the `.` is what makes it safe: [AtlasConnectionIds.slug]
         * produces `[a-z0-9-]+`, so no environment anyone can name will ever collide with one of these,
         * and a stored pointer says plainly which list it points into.
         */
        internal fun idOf(name: String): String = PREFIX + AtlasConnectionIds.slug(name)

        internal fun connectionIdOf(environmentName: String, kind: ConnectionKind): String =
            idOf(environmentName) + "-" + AtlasConnectionIds.slug(kind.display)

        /** True for an id this store minted — what tells the settings page a node is not its to edit. */
        internal fun isShared(id: String): Boolean = id.startsWith(PREFIX)

        private const val PREFIX = "shared."

        /**
         * Drops what cannot mean anything, so a hand-edited or badly merged committed file can never
         * leave consumers with something to defend against. Called from both [getState] and [loadState],
         * so "what is valid" has one home — the same contract as [AtlasEnvironments.sanitize].
         */
        internal fun sanitize(state: State): State {
            state.environments.removeAll { it.name.isBlank() }
            // A bad merge is the only way to get two entries with one name; the later one is the
            // incoming side, mirroring how the other stores resolve it.
            state.environments = state.environments
                .associateBy { it.name.trim().lowercase() }.values.toMutableList()
            state.environments.forEach { environment ->
                environment.name = environment.name.trim()
                environment.connections.removeAll { it.baseUrl.isBlank() || it.resolvedKind() == null }
                val seen = HashSet<ConnectionKind>()
                environment.connections.removeAll { !seen.add(it.resolvedKind()!!) }
            }
            return state
        }
    }
}
