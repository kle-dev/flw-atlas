package com.flowable.atlas.settings.connections

import com.flowable.atlas.design.DesignAuthMode
import com.flowable.atlas.environment.AtlasConnectionIds
import com.flowable.atlas.environment.AtlasEnvironments
import com.flowable.atlas.environment.BaseUrls
import com.flowable.atlas.environment.ConnectionKind

/**
 * The editable copy of the environment catalog that the settings page works on.
 *
 * The page never mutates [AtlasEnvironments] directly, and that is not a style preference: without a
 * draft, *Cancel* could not undo a removed environment or a rename, because those would already have
 * happened — and a settings dialog that keeps changes you cancelled is the worst kind of surprise in a
 * page whose whole job is being trustworthy about what is configured.
 *
 * Secrets are **not** here. They live in the PasswordSafe keyed by base URL and are written by the
 * detail forms on apply, exactly as they were before.
 *
 * Pure: no Swing, no services, no IDE. Everything the page can do to the catalog is a method here with
 * a test beside it.
 */
class ConnectionsDraft private constructor(
    val environments: MutableList<Env>,
    val connections: MutableList<Conn>,
) {

    class Env(var id: String, var name: String, var requireConfirmation: Boolean)

    class Conn(
        var id: String,
        var environmentId: String,
        val kind: ConnectionKind,
        var baseUrl: String,
        var username: String,
        var authMode: DesignAuthMode,
    )

    // ---- queries ------------------------------------------------------------------------------

    fun environment(id: String): Env? = environments.firstOrNull { it.id == id }

    fun connection(id: String): Conn? = connections.firstOrNull { it.id == id }

    fun connectionsOf(environmentId: String): List<Conn> =
        // Fixed order, so the tree never reshuffles under the user: Design first, then Work.
        connections.filter { it.environmentId == environmentId }.sortedBy { it.kind.ordinal }

    /** The kinds [environmentId] can still take — what the "+" popup offers. */
    fun freeKinds(environmentId: String): List<ConnectionKind> {
        val taken = connectionsOf(environmentId).mapTo(HashSet()) { it.kind }
        return ConnectionKind.entries.filterNot { it in taken }
    }

    /** True when some environment lacks [kind] — whether the picker offers "Add a … connection". */
    fun anyEnvironmentLacks(kind: ConnectionKind): Boolean =
        environments.any { env -> connectionsOf(env.id).none { it.kind == kind } }

    // ---- edits --------------------------------------------------------------------------------

    fun addEnvironment(name: String): Env {
        val unique = uniqueName(name)
        val env = Env(AtlasConnectionIds.newId(unique, takenEnvironmentIds()), unique, false)
        environments.add(env)
        return env
    }

    /** Adds the [kind] connection of [environmentId], or null when that slot is already filled. */
    fun addConnection(environmentId: String, kind: ConnectionKind): Conn? {
        if (connectionsOf(environmentId).any { it.kind == kind }) return null
        val environmentName = environment(environmentId)?.name.orEmpty()
        val conn = Conn(
            AtlasConnectionIds.newConnectionId(environmentName, kind.display, takenConnectionIds()),
            environmentId, kind, "", "", DesignAuthMode.BASIC,
        )
        connections.add(conn)
        return conn
    }

    /** Removing an environment removes its connections — an orphan would only be re-homed anyway. */
    fun removeEnvironment(id: String) {
        environments.removeAll { it.id == id }
        connections.removeAll { it.environmentId == id }
    }

    fun removeConnection(id: String) {
        connections.removeAll { it.id == id }
    }

    /**
     * Duplicates an environment with its connections — the "define once, clone for the next stage"
     * gesture: copy QA, rename it UAT, change two URLs. Secrets are not copied because they are keyed
     * by base URL, so a copy still pointing at the same server keeps working on its own.
     */
    fun copyEnvironment(id: String): Env? {
        val source = environment(id) ?: return null
        val copy = addEnvironment(source.name)   // uniqueName turns "QA" into "QA (2)"
        copy.requireConfirmation = source.requireConfirmation
        connectionsOf(id).forEach { original ->
            addConnection(copy.id, original.kind)?.let {
                it.baseUrl = original.baseUrl
                it.username = original.username
                it.authMode = original.authMode
            }
        }
        return copy
    }

    /**
     * Moves an environment by [delta]. Order is editable because DEV → QA → UAT → PROD is a pipeline
     * and every picker renders in this order; alphabetically PROD would land second.
     */
    fun moveEnvironment(id: String, delta: Int): Boolean {
        val from = environments.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in environments.indices) return false
        environments.add(to, environments.removeAt(from))
        return true
    }

    // ---- validation ---------------------------------------------------------------------------

    /** The first problem a human should fix, or null. Wording is the dialog's message. */
    fun validate(): String? {
        environments.firstOrNull { it.name.isBlank() }?.let { return "An environment needs a name." }
        val seenNames = HashSet<String>()
        environments.firstOrNull { !seenNames.add(it.name.lowercase()) }
            ?.let { return "There is already an environment called \"${it.name}\"." }
        connections.firstOrNull { it.baseUrl.isBlank() }?.let {
            return "The ${it.kind.display} connection of \"${environment(it.environmentId)?.name}\" needs a URL."
        }
        // Two environments on one URL are deliberately allowed. They share the one PasswordSafe record
        // that URL has — which is the right answer, because it is the same server and the same login.
        // One Design server hosting a DEV workspace and a QA workspace is an ordinary setup, and a
        // validation rule derived from how credentials happen to be keyed has no business forbidding it.
        return null
    }

    // ---- comparison and persistence -----------------------------------------------------------

    /** A value the page compares for `isModified()`; ids included, since a copy is a real change. */
    fun snapshot(): List<String> =
        environments.map { "E ${it.id}|${it.name}|${it.requireConfirmation}" } +
            connections.map { "C ${it.id}|${it.environmentId}|${it.kind}|${it.baseUrl}|${it.username}|${it.authMode}" }

    fun toEnvironmentStates(): List<AtlasEnvironments.EnvironmentState> =
        environments.map { AtlasEnvironments.EnvironmentState(it.id, it.name.trim(), it.requireConfirmation) }

    fun toConnectionStates(): List<AtlasEnvironments.ConnectionState> = connections.map {
        AtlasEnvironments.ConnectionState(
            it.id, it.environmentId, it.kind, BaseUrls.normalize(it.kind, it.baseUrl), it.username.trim(), it.authMode,
        )
    }

    private fun takenEnvironmentIds(): Set<String> = environments.mapTo(HashSet()) { it.id }

    private fun takenConnectionIds(): Set<String> = connections.mapTo(HashSet()) { it.id }

    private fun uniqueName(wanted: String): String {
        val taken = environments.mapTo(HashSet()) { it.name.lowercase() }
        val base = wanted.trim().ifBlank { "New Environment" }
        if (base.lowercase() !in taken) return base
        var suffix = 2
        while ("${base.lowercase()} ($suffix)" in taken) suffix++
        return "$base ($suffix)"
    }

    companion object {

        fun from(catalog: AtlasEnvironments): ConnectionsDraft {
            val environments = catalog.environments()
                .mapTo(mutableListOf()) { Env(it.id, it.name, it.requireConfirmation) }
            val connections = catalog.connections()
                .mapTo(mutableListOf()) { Conn(it.id, it.environmentId, it.kind, it.baseUrl, it.username, it.authMode) }
            return ConnectionsDraft(environments, connections)
        }
    }
}
