package com.flowable.atlas.environment

import com.flowable.atlas.environment.auth.AuthMode
import com.flowable.atlas.events.AtlasEvents
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * The IDE-wide catalog of Flowable **environments** (DEV1, DEV2, QA, UAT, PROD …) and the typed
 * **connections** inside them. Application-level on purpose: a developer's server list is the same in
 * every Flowable project they open, and re-entering four URLs per repository is the complaint this
 * exists to answer.
 *
 * ### Two entities, and at most one connection per kind
 *
 * An environment holds **at most one** connection of each [ConnectionKind] — and, importantly, may
 * hold any subset of them. `qa` legitimately has a running app and no Design server; `dev1` has both;
 * either may add the Control and Hub addresses, which are links rather than servers Atlas talks to.
 *
 * Two *different* environments may point at the same server, and that is not a mistake to guard
 * against: one Design server commonly hosts a DEV workspace and a QA workspace. They share the single
 * PasswordSafe record that URL has, which is the right answer — same server, same login.
 *
 * The tempting cheaper shape — one record per environment with a `designBaseUrl` and a `workBaseUrl`
 * field — was rejected because it encodes "absent" as a blank string in every consumer. The opposite
 * extreme, several connections of one kind per environment, was rejected because it costs the whole
 * point: with one per kind every picker is a list of *environment names* (`DEV1 · DEV2 · QA · PROD`),
 * which is the dropdown that was actually asked for, and every status line can say `QA` instead of a
 * URL — which is what makes a 300px-wide tool window readable. Someone who genuinely runs two Design
 * servers side by side makes two environments; that is one click and every other surface stays
 * legible.
 *
 * What *justifies* the second entity is [EnvironmentState.requireConfirmation]: protection is a fact
 * about the environment, so a PROD Work connection added six months later inherits it instead of
 * quietly defaulting to unprotected.
 *
 * ### Selecting one is not the same as defining one
 *
 * This class knows nothing about which connection a project uses — that is `AtlasConnectionSelection`,
 * per project, per Flowable sub-project and per kind. There is deliberately **no** "active
 * environment" anywhere: the Design pull and the Expression Playground hold independent pointers,
 * which is what makes "QA is my runtime, and I still pull models from DEV1" the normal case rather
 * than a special one.
 *
 * ### Storage
 *
 * Its own file, not `flowable-atlas.xml` — that name already carries `FlowableAtlasSettings`
 * (app-level), `FlowableAtlasProjectSettings` and `ModelConstantsSettings` (project-level), and a
 * sandbox copy still holds orphaned `inspectBaseUrl` options from when those fields were app-wide. A
 * fourth tenant of one file is the kind of collision that surfaces as data loss.
 * Roaming stays on: the environment list should follow the developer through Settings Sync. The
 * secrets do not — they live in the PasswordSafe, keyed by base URL
 * ([com.flowable.atlas.design.DesignCredentials], [com.flowable.atlas.expr.inspect.InspectCredentials]).
 */
@Service(Service.Level.APP)
@State(name = "FlowableAtlasEnvironments", storages = [Storage("flowable-atlas-environments.xml")])
class AtlasEnvironments : PersistentStateComponent<AtlasEnvironments.State> {

    /** One environment. A no-arg primary constructor is required for XML deserialization. */
    class EnvironmentState() {
        var id: String = ""
        var name: String = ""

        /**
         * The UI calls this **Protected**: confirm before pulling from, or evaluating against, any
         * connection in this environment. Not named `isProtected` — `protected` is a Kotlin keyword,
         * the bean property would be spelled differently from the field, and no other boolean in this
         * plugin carries an `is` prefix.
         */
        var requireConfirmation: Boolean = false

        constructor(id: String, name: String, requireConfirmation: Boolean = false) : this() {
            this.id = id
            this.name = name
            this.requireConfirmation = requireConfirmation
        }
    }

    /** One connection inside an environment. Its [kind] is its name — see [AtlasConnection]. */
    class ConnectionState() {
        var id: String = ""
        var environmentId: String = ""

        /**
         * The [ConnectionKind] **by name, as a `String` with a blank default** — deliberately not
         * typed as the enum. IntelliJ serializes state through `SkipDefaultsSerializationFilter`, so a
         * `kind: ConnectionKind = DESIGN` field would write no attribute at all for every Design
         * connection; the day the enum's first constant changes, every stored record silently becomes
         * something else. With a blank default no meaningful value is ever the default, so the
         * attribute is always written, and an absent or unknown kind drops the record instead of
         * guessing. ([authMode] keeps its enum type: `BASIC` is a stable default and
         * `FlowableAtlasProjectSettings.designAuthMode` already behaves exactly that way.)
         */
        var kind: String = ""
        var baseUrl: String = ""

        /** Design's username lived only in the keychain before; it is authoritative here now. */
        var username: String = ""

        /** Only meaningful for [ConnectionKind.DESIGN]; a running app always uses basic auth. */
        var authMode: AuthMode = AuthMode.BASIC

        constructor(
            id: String,
            environmentId: String,
            kind: ConnectionKind,
            baseUrl: String,
            username: String = "",
            authMode: AuthMode = AuthMode.BASIC,
        ) : this() {
            this.id = id
            this.environmentId = environmentId
            this.kind = kind.name
            this.baseUrl = baseUrl
            this.username = username
            this.authMode = authMode
        }

        internal fun resolvedKind(): ConnectionKind? = ConnectionKind.byName(kind)
    }

    class State {
        var environments: MutableList<EnvironmentState> = mutableListOf()
        var connections: MutableList<ConnectionState> = mutableListOf()
    }

    private var state = State()
    private val lock = Any()

    // ---- PersistentStateComponent -----------------------------------------------------------

    /**
     * Note what is *not* here: a `sortBy`. `FlowableAtlasProjectSettings.getState` sorts its
     * sub-projects by path, and mirroring that would be a bug — DEV/QA/UAT/PROD is the order in the
     * user's head, and alphabetically PROD lands second. The list order *is* the user's order, which
     * is also why the editor offers move-up/move-down.
     */
    override fun getState(): State = synchronized(lock) { sanitize(state) }

    override fun loadState(newState: State) {
        synchronized(lock) { state = sanitize(newState) }
    }

    // ---- reads: snapshots only --------------------------------------------------------------

    fun environments(): List<AtlasEnvironmentSnapshot> = synchronized(lock) {
        state.environments.map { AtlasEnvironmentSnapshot(it.id, it.name, it.requireConfirmation) }
    }

    fun environment(id: String): AtlasEnvironmentSnapshot? = environments().firstOrNull { it.id == id }

    /** Every connection, or only those of [kind]. In catalog order, which is the user's order. */
    fun connections(kind: ConnectionKind? = null): List<AtlasConnection> = synchronized(lock) {
        val byId = state.environments.associateBy { it.id }
        state.connections.mapNotNull { connection ->
            val resolved = connection.resolvedKind() ?: return@mapNotNull null
            if (kind != null && resolved != kind) return@mapNotNull null
            val environment = byId[connection.environmentId]
            AtlasConnection(
                id = connection.id,
                kind = resolved,
                baseUrl = connection.baseUrl,
                username = connection.username,
                authMode = connection.authMode,
                environmentId = connection.environmentId,
                environmentName = environment?.name.orEmpty(),
                // Unreachable after sanitize(); if it ever happened, an environment we know nothing
                // about is exactly the one worth asking about.
                requiresConfirmation = environment?.requireConfirmation ?: true,
            )
        }
    }

    fun connection(id: String): AtlasConnection? =
        if (id.isBlank()) null else connections().firstOrNull { it.id == id }

    /** The one connection of [kind] in [environmentId], or null — the invariant makes this total. */
    fun connection(environmentId: String, kind: ConnectionKind): AtlasConnection? =
        connections(kind).firstOrNull { it.environmentId == environmentId }

    /** Which kinds [environmentId] already fills — the editor's "+" only offers the rest. */
    fun occupiedKinds(environmentId: String): Set<ConnectionKind> =
        connections().filter { it.environmentId == environmentId }.mapTo(HashSet()) { it.kind }

    /** True when [environmentId] holds a Design *and* a Work server — the "one environment for both"
     *  case. Named for the two it means: the link-only kinds are not part of being fully configured,
     *  and counting them here would make an environment with a Control URL look half-finished. */
    fun hasBothServers(environmentId: String): Boolean =
        occupiedKinds(environmentId).containsAll(ConnectionKind.SERVERS)

    /** Every connection of [kind] addressing [baseUrl] — more than one when stages share a server. */
    fun byBaseUrl(kind: ConnectionKind, baseUrl: String): List<AtlasConnection> =
        if (baseUrl.isBlank()) emptyList()
        else connections(kind).filter { BaseUrls.sameUrl(kind, it.baseUrl, baseUrl) }

    // ---- mutations ---------------------------------------------------------------------------

    fun addEnvironment(name: String, requireConfirmation: Boolean = false): String = mutate {
        val id = AtlasConnectionIds.newId(name, state.environments.mapTo(HashSet()) { it.id })
        state.environments.add(EnvironmentState(id, name.trim(), requireConfirmation))
        id
    }

    fun updateEnvironment(id: String, name: String, requireConfirmation: Boolean) = mutate {
        state.environments.firstOrNull { it.id == id }?.let {
            it.name = name.trim()
            it.requireConfirmation = requireConfirmation
        }
    }

    /** Removes the environment **and its connections** — an orphan would only be re-homed anyway. */
    fun removeEnvironment(id: String) = mutate {
        state.environments.removeAll { it.id == id }
        state.connections.removeAll { it.environmentId == id }
    }

    /**
     * Adds the [kind] connection of [environmentId], or returns **null** when that environment
     * already has one. A nullable return rather than a silent no-op: the slot being taken is a real
     * answer the caller has to render ("QA already has a Design server"), and a swallowed add would
     * show up later as a connection that mysteriously never appeared.
     */
    fun addConnection(
        environmentId: String,
        kind: ConnectionKind,
        baseUrl: String,
        username: String = "",
        authMode: AuthMode = AuthMode.BASIC,
    ): String? = mutate {
        if (state.connections.any { it.environmentId == environmentId && it.resolvedKind() == kind }) {
            return@mutate null
        }
        val environmentName = state.environments.firstOrNull { it.id == environmentId }?.name.orEmpty()
        val id = AtlasConnectionIds.newConnectionId(
            environmentName, kind.display, state.connections.mapTo(HashSet()) { it.id },
        )
        state.connections.add(
            ConnectionState(id, environmentId, kind, BaseUrls.normalize(kind, baseUrl), username.trim(), authMode),
        )
        id
    }

    fun updateConnection(id: String, baseUrl: String, username: String, authMode: AuthMode) = mutate {
        state.connections.firstOrNull { it.id == id }?.let {
            val kind = it.resolvedKind() ?: ConnectionKind.DESIGN
            it.baseUrl = BaseUrls.normalize(kind, baseUrl)
            it.username = username.trim()
            it.authMode = authMode
        }
    }

    fun removeConnection(id: String) = mutate { state.connections.removeAll { it.id == id } }

    /** The editor's single Apply: one replacement, one notification, whatever the user edited. */
    fun replaceAll(environments: List<EnvironmentState>, connections: List<ConnectionState>) = mutate {
        state.environments = environments.toMutableList()
        state.connections = connections.toMutableList()
    }

    /**
     * Takes the lock, runs [block], **releases it, then** publishes. Publishing while holding the lock
     * is a deadlock invitation given the topic's "publishers may fire from any thread" contract — a
     * subscriber that reads the catalog back would re-enter it. The wrapper also means a mutator
     * cannot forget to notify.
     */
    private inline fun <T> mutate(block: () -> T): T {
        val result = synchronized(lock) { block().also { state = sanitize(state) } }
        AtlasEvents.environmentsChanged()
        return result
    }

    companion object {
        fun getInstance(): AtlasEnvironments = service()

        /**
         * Drops what cannot mean anything and repairs what can, so a hand-edited or badly merged file
         * can never leave the catalog in a state consumers have to defend against. Called from
         * [loadState] *and* [getState] — one home for "what is valid".
         */
        internal fun sanitize(state: State): State {
            state.environments.removeAll { it.id.isBlank() || it.name.isBlank() }
            // Duplicate ids: last wins, mirroring FlowableAtlasProjectSettings.loadState — a bad VCS
            // merge is the only way to get here, and the later entry is the incoming one.
            state.environments = state.environments.associateBy { it.id }.values.toMutableList()

            state.connections.removeAll { it.id.isBlank() || it.baseUrl.isBlank() || it.resolvedKind() == null }
            state.connections = state.connections.associateBy { it.id }.values.toMutableList()

            rehomeOrphans(state)

            // One connection per (environment, kind), keeping the FIRST — the earlier entry is the one
            // the user has been working with and the one a project's pointer most likely names. Two
            // *different* environments on the same URL are fine and are left alone: that is one server
            // serving two stages, and they share its saved credential, which is correct.
            // Order matters — re-homing first means a recovered connection is held to this rule too.
            val slots = HashSet<String>()
            state.connections.removeAll { connection ->
                val kind = connection.resolvedKind() ?: return@removeAll true
                !slots.add(connection.environmentId + " " + kind.name)
            }
            return state
        }

        /**
         * A connection whose environment is gone is **re-homed, never dropped**: losing it loses a URL,
         * and "nobody has to reconfigure anything" is the promise this whole feature is built on. The
         * synthesized environment is named after the connection's host and is created **protected** —
         * an environment we know nothing about is exactly the one worth asking about.
         */
        private fun rehomeOrphans(state: State) {
            val known = state.environments.mapTo(HashSet()) { it.id }
            val orphans = state.connections.filter { it.environmentId !in known }
            if (orphans.isEmpty()) return
            val byName = state.environments.associateBy { it.name }.toMutableMap()
            for (orphan in orphans) {
                val name = BaseUrls.host(orphan.baseUrl).ifBlank { "Recovered" }
                val environment = byName.getOrPut(name) {
                    val id = AtlasConnectionIds.newId(name, state.environments.mapTo(HashSet()) { it.id })
                    EnvironmentState(id, name, requireConfirmation = true).also { state.environments.add(it) }
                }
                orphan.environmentId = environment.id
            }
        }
    }
}
