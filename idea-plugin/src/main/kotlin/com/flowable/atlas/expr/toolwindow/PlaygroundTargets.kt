package com.flowable.atlas.expr.toolwindow

import com.flowable.atlas.environment.AtlasCatalog
import com.flowable.atlas.environment.AtlasConnection
import com.flowable.atlas.environment.AtlasConnectionSelection
import com.flowable.atlas.environment.AtlasEnvironments
import com.flowable.atlas.environment.BaseUrls
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.environment.ConnectionLabels
import com.flowable.atlas.environment.auth.AtlasCredentials
import com.flowable.atlas.environment.auth.BrowserSessions
import com.flowable.atlas.expr.inspect.InspectSessionTargets
import com.flowable.atlas.expr.inspect.PasteWorkUrlDialog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * What a backend evaluation can run against: one of the defined environments, or one of the URLs
 * pasted into this IDE session ([InspectSessionTargets]) — which are not environments and never become
 * one by accident.
 *
 * A type rather than "an [AtlasConnection], or null meaning the ad-hoc one". That encoding held exactly
 * one ad-hoc target because `null` is not a value you can have two of, and the second pasted link
 * therefore evicted the first.
 */
internal sealed interface Target {
    data class Env(val connection: AtlasConnection) : Target

    /** A pasted URL, alive for this IDE session and written down nowhere. */
    data class Session(val baseUrl: String) : Target

    /** Nothing to evaluate against — offered only while it is the actual state. */
    data object None : Target
}

/**
 * The backend card's target logic without its Swing: which target is in use, what the picker offers,
 * what a pasted link does, what forgetting and saving do. The panel binds a combo to this and asks it
 * what to say; a test drives it without a window.
 */
internal class PlaygroundTargets(private val project: Project) {

    /**
     * The session target the card is pointed at, or null when it follows the project's chosen
     * environment. Panel state, not catalog state: the targets themselves are IDE-wide so a second
     * playground can see them, but *which one this card uses* is a local choice.
     */
    private var selectedSessionUrl: String? = null

    /**
     * Set by the (any-thread) event listener when a Work environment was picked somewhere else, and
     * consumed on the EDT: that pick is a decision about this card too, so it wins over a session target
     * selected here. Nothing outside can point *at* a session target, so there is no reverse.
     */
    @Volatile
    var followEnvironmentPick = false

    /**
     * What the card is pointed at. A session target the user picked wins, because picking it is the
     * most recent thing they said; otherwise the project's chosen environment answers, exactly as it
     * does everywhere else in the plugin. A target forgotten from another playground simply stops being
     * the answer — this reads through to [InspectSessionTargets] rather than trusting a field.
     */
    fun current(): Target {
        selectedSessionUrl?.takeIf { InspectSessionTargets.contains(it) }?.let { return Target.Session(it) }
        val connection = AtlasConnectionSelection.selected(project, ConnectionKind.WORK) ?: return Target.None
        return Target.Env(connection)
    }

    fun connection(): AtlasConnection? = (current() as? Target.Env)?.connection

    fun baseUrl(): String = when (val t = current()) {
        is Target.Env -> t.connection.baseUrl
        is Target.Session -> t.baseUrl
        Target.None -> ""
    }

    /** Every environment, then every URL pasted into this session; "no environment yet" only while it is true. */
    fun choices(): List<Target> {
        // Drop a selection whose target another playground (or this one) has forgotten, so the field
        // cannot quietly resurrect it the next time the list changes.
        if (selectedSessionUrl?.let { !InspectSessionTargets.contains(it) } == true) selectedSessionUrl = null
        val target = current()
        return buildList {
            if (target == Target.None) add(Target.None)
            AtlasCatalog.connections(project, ConnectionKind.WORK).forEach { add(Target.Env(it)) }
            InspectSessionTargets.all().forEach { add(Target.Session(it)) }
        }
    }

    /** What the picker calls a target — the environment's name, or host and path plus "(this session)". */
    fun label(target: Target): String = when (target) {
        is Target.Env -> ConnectionLabels.pickerItem(target.connection)
        // Host, port and path — the scheme is the only part that never tells two of these apart.
        is Target.Session -> "${BaseUrls.withoutScheme(target.baseUrl)} (this session)"
        Target.None -> "no environment yet"
    }

    fun tooltip(target: Target): String? = when (target) {
        is Target.Env -> ConnectionLabels.tooltip(
            ConnectionKind.WORK, AtlasConnectionSelection.Resolution.Selected(target.connection, true),
        )
        is Target.Session -> "<html>${target.baseUrl}<br>Kept for this IDE session only.</html>"
        Target.None -> null
    }

    fun choose(target: Target) {
        when (target) {
            // Picking an environment *is* the switch. It also ends the session target: two answers to
            // "which app?" is the state this card is not allowed to be in.
            is Target.Env -> {
                selectedSessionUrl = null
                AtlasConnectionSelection.select(project, ConnectionKind.WORK, target.connection.id)
            }
            is Target.Session -> selectedSessionUrl = target.baseUrl
            Target.None -> Unit
        }
    }

    /** Called on the EDT after a catalog or selection event: honour a pick made elsewhere. */
    fun adopt() {
        if (followEnvironmentPick) {
            followEnvironmentPick = false
            selectedSessionUrl = null
        }
    }

    /** What [applyPasted] found out, for the message the card shows. */
    class Pasted(val where: String, val repeated: Boolean)

    /**
     * Points the card at what the paste dialog resolved. A known app is selected as the environment; an
     * unknown one becomes a session target — *added*, not swapped in, so a second pasted link is a second
     * app to look at and the first stays a click away. Nothing is created and nothing is written: its
     * credentials go to the in-memory session store as a basic Authorization header.
     */
    fun applyPasted(result: PasteWorkUrlDialog.Result): Pasted? {
        if (result.baseUrl.isBlank()) return null
        var repeated = false
        val known = result.connectionId
        if (known != null) {
            selectedSessionUrl = null
            AtlasConnectionSelection.select(project, ConnectionKind.WORK, known)
        } else {
            repeated = InspectSessionTargets.contains(result.baseUrl)
            selectedSessionUrl = InspectSessionTargets.add(result.baseUrl)
            if (result.username.isNotBlank()) {
                val encoded = java.util.Base64.getEncoder()
                    .encodeToString("${result.username}:${result.password}".toByteArray())
                BrowserSessions.set(result.baseUrl, mapOf("Authorization" to "Basic $encoded"))
            }
        }
        return Pasted(connection()?.environmentName ?: BaseUrls.withoutScheme(result.baseUrl), repeated)
    }

    /**
     * Drops a target *and* whatever was captured for it: a cookie left behind for a URL that is no
     * longer in any list is a credential nobody can see and nobody asked to keep.
     */
    fun forget(baseUrl: String) {
        InspectSessionTargets.remove(baseUrl)
        BrowserSessions.clear(baseUrl)
    }

    sealed interface Saved {
        data class Ok(val name: String) : Saved
        data class AlreadyHasApp(val name: String) : Saved
    }

    /**
     * Turns a session target into a real environment — the one thing a one-off cannot do for itself. The
     * captured basic-auth pair goes with it, into the PasswordSafe; a captured SSO session stays where it
     * is, since the catalog has nowhere to put it and dropping it would break the next evaluation.
     */
    fun saveAs(baseUrl: String, name: String, protected: Boolean): Saved {
        val catalog = AtlasEnvironments.getInstance()
        // The developer's own list, not the merged one: this creates, and only that list can be written
        // to. A name matching a *shared* environment is fine and means "my own QA".
        val environmentId = catalog.environments().firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
            ?: catalog.addEnvironment(name, protected)
        val captured = BrowserSessions.basicAuth(baseUrl)
        val connectionId = catalog.addConnection(environmentId, ConnectionKind.WORK, baseUrl, captured?.first.orEmpty())
            ?: return Saved.AlreadyHasApp(name)   // the dialog validates this; the catalog changed under it
        InspectSessionTargets.remove(baseUrl)
        selectedSessionUrl = null
        AtlasConnectionSelection.select(project, ConnectionKind.WORK, connectionId)
        captured?.let { (username, password) ->
            // The PasswordSafe is the OS keychain, which can block or prompt.
            ApplicationManager.getApplication().executeOnPooledThread { AtlasCredentials.save(baseUrl, username, password) }
        }
        return Saved.Ok(name)
    }
}
