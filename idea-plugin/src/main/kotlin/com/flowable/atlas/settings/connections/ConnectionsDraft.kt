package com.flowable.atlas.settings.connections

import com.flowable.atlas.design.DesignAuthMode
import com.flowable.atlas.environment.AtlasCatalog
import com.flowable.atlas.environment.AtlasConnectionIds
import com.flowable.atlas.environment.AtlasEnvironments
import com.flowable.atlas.environment.SharedEnvironments
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
    /** Environment names the project's committed file already defines, lowercased. */
    private val sharedNames: Set<String>,
) {

    /** [shared] entries come from the project's committed file — shown, never edited here. */
    class Env(var id: String, var name: String, var requireConfirmation: Boolean, val shared: Boolean = false)

    class Conn(
        var id: String,
        var environmentId: String,
        val kind: ConnectionKind,
        var baseUrl: String,
        var username: String,
        var authMode: DesignAuthMode,
        val shared: Boolean = false,
    )

    /**
     * What Apply should write to — or delete from — the project's committed file.
     *
     * Held here rather than written on click for the same reason the rest of this class exists: a
     * settings page that changed a *committed file* the moment you pressed a toolbar button would make
     * *Cancel* a lie about the one change with consequences beyond your own machine.
     */
    val sharedExports = mutableSetOf<String>()

    val sharedRemovals = mutableSetOf<String>()

    /** True when the project's file already defines an environment of this name. */
    fun isSharedByProject(name: String): Boolean = name.trim().lowercase() in sharedNames

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

    /** True when this page may edit [environmentId] — false for what the project defines. */
    fun isEditable(environmentId: String): Boolean = environment(environmentId)?.shared == false

    /** Adds the [kind] connection of [environmentId], or null when the slot is filled or not ours. */
    fun addConnection(environmentId: String, kind: ConnectionKind): Conn? {
        if (!isEditable(environmentId)) return null
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
        if (!isEditable(id)) return
        environments.removeAll { it.id == id }
        connections.removeAll { it.environmentId == id }
    }

    fun removeConnection(id: String) {
        connections.removeAll { it.id == id && !it.shared }
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
        if (!isEditable(id)) return false
        val from = environments.indexOfFirst { it.id == id }
        val to = from + delta
        // Never past the shared block: its order is the committed file's, not this list's.
        if (from < 0 || to !in environments.indices || environments[to].shared) return false
        environments.add(to, environments.removeAt(from))
        return true
    }

    // ---- validation ---------------------------------------------------------------------------

    /** The first problem a human should fix, or null. Wording is the dialog's message. */
    fun validate(): String? {
        // Only what this page can actually fix. A shared entry comes from a committed file: holding
        // Apply hostage to a colleague's typo would block the user from saving their own work, and the
        // reader of that file is git, not this dialog.
        val own = environments.filterNot { it.shared }
        own.firstOrNull { it.name.isBlank() }?.let { return "An environment needs a name." }
        val seenNames = HashSet<String>()
        own.firstOrNull { !seenNames.add(it.name.lowercase()) }
            ?.let { return "There is already an environment called \"${it.name}\"." }
        connections.filterNot { it.shared }.firstOrNull { it.baseUrl.isBlank() }?.let {
            return "The ${it.kind.display} connection of \"${environment(it.environmentId)?.name}\" needs a URL."
        }
        // Two environments on one URL are deliberately allowed. They share the one PasswordSafe record
        // that URL has — which is the right answer, because it is the same server and the same login.
        // One Design server hosting a DEV workspace and a QA workspace is an ordinary setup, and a
        // validation rule derived from how credentials happen to be keyed has no business forbidding it.
        return null
    }

    // ---- comparison and persistence -----------------------------------------------------------

    /**
     * A value the page compares for `isModified()`; ids included, since a copy is a real change.
     *
     * Shared entries are left out — nothing here can change them, and a `git pull` landing while the
     * dialog is open would otherwise register as *your* unsaved edit. What a pending share *is* part
     * of it, because that is a change this page will make.
     */
    fun snapshot(): List<String> =
        environments.filterNot { it.shared }.map { "E ${it.id}|${it.name}|${it.requireConfirmation}" } +
            connections.filterNot { it.shared }
                .map { "C ${it.id}|${it.environmentId}|${it.kind}|${it.baseUrl}|${it.username}|${it.authMode}" } +
            sharedExports.sorted().map { "S+ $it" } +
            sharedRemovals.sorted().map { "S- $it" }

    fun toEnvironmentStates(): List<AtlasEnvironments.EnvironmentState> =
        environments.filterNot { it.shared }
            .map { AtlasEnvironments.EnvironmentState(it.id, it.name.trim(), it.requireConfirmation) }

    fun toConnectionStates(): List<AtlasEnvironments.ConnectionState> = connections.filterNot { it.shared }.map {
        AtlasEnvironments.ConnectionState(
            it.id, it.environmentId, it.kind, BaseUrls.normalize(it.kind, it.baseUrl), it.username.trim(), it.authMode,
        )
    }

    private fun takenEnvironmentIds(): Set<String> = environments.mapTo(HashSet()) { it.id }

    private fun takenConnectionIds(): Set<String> = connections.mapTo(HashSet()) { it.id }

    /**
     * Only the developer's **own** names collide. A shared environment of the same name is shadowed by
     * a local one on purpose ([com.flowable.atlas.environment.AtlasCatalog]), so copying the project's
     * `QA` to change one URL has to be allowed to produce a local `QA` — turning it into `QA (2)` would
     * leave the project's version still in every picker, which is the opposite of what was asked for.
     */
    private fun uniqueName(wanted: String): String {
        val taken = environments.filterNot { it.shared }.mapTo(HashSet()) { it.name.lowercase() }
        val base = wanted.trim().ifBlank { "New Environment" }
        if (base.lowercase() !in taken) return base
        var suffix = 2
        while ("${base.lowercase()} ($suffix)" in taken) suffix++
        return "$base ($suffix)"
    }

    companion object {

        /**
         * The developer's own list first, then whatever the project shares that it does not already
         * shadow — the same order and the same shadowing rule the pickers use, so this page shows the
         * list the rest of the plugin will offer rather than a second, differently-filtered one.
         */
        fun from(catalog: AtlasEnvironments, shared: SharedEnvironments): ConnectionsDraft {
            val environments = catalog.environments()
                .mapTo(mutableListOf()) { Env(it.id, it.name, it.requireConfirmation) }
            val connections = catalog.connections()
                .mapTo(mutableListOf()) { Conn(it.id, it.environmentId, it.kind, it.baseUrl, it.username, it.authMode) }
            val sharedEnvironments = shared.environments()
            val visible = AtlasCatalog.mergeEnvironments(catalog.environments(), sharedEnvironments)
                .mapTo(HashSet()) { it.id }
            sharedEnvironments.filter { it.id in visible }.forEach {
                environments += Env(it.id, it.name, it.requireConfirmation, shared = true)
            }
            shared.connections().filter { it.environmentId in visible }.forEach {
                connections += Conn(
                    it.id, it.environmentId, it.kind, it.baseUrl, it.username, it.authMode, shared = true,
                )
            }
            return ConnectionsDraft(
                environments, connections, sharedEnvironments.mapTo(HashSet()) { it.name.trim().lowercase() },
            )
        }
    }
}
