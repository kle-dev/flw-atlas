package com.flowable.atlas.settings.connections

import com.flowable.atlas.design.DesignAuthMode
import com.flowable.atlas.design.DesignClient
import com.flowable.atlas.design.DesignCreateTokenDialog
import com.flowable.atlas.design.DesignCredentials
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * The Flowable Design half of one environment: server URL, how it authenticates, and the round-trip
 * that proves it works.
 *
 * Lifted out of the old single-connection `DesignConnectionPanel` rather than rewritten — the async
 * behaviour there was hard-won and is the asset: both secrets are read in **one** off-EDT keychain
 * trip; both credential rows stay in the layout with only the selected one visible, so switching auth
 * mode loses neither secret (mirroring the two separate keychain records); a `populating` flag keeps
 * programmatic combo changes from firing listeners; and every async callback checks [disposed] before
 * touching Swing.
 *
 * What did *not* come along: the workspace picker, the app list and the target folder. Those are facts
 * about a **project**, not about a server — two projects sharing one DEV server pull different apps —
 * and they are chosen in the Atlas Hub, beside the models they fetch.
 *
 * The one rename: *Refresh Workspaces* is now **Test Connection**. It always was the connection test;
 * with the pickers gone, its old name would describe something this form no longer shows.
 */
class DesignConnectionForm(private val project: Project) : Disposable {

    private val LOG = logger<DesignConnectionForm>()

    /** One connection's secrets as the form holds them. A value type, so "changed?" is `!=`. */
    private data class Secrets(
        val baseUrl: String = "",
        val username: String = "",
        val password: String = "",
        val token: String = "",
    )

    private val baseUrlField = JBTextField().apply {
        emptyText.text = "http://design.example.com/flowable-design"
    }
    private val authModeCombo = JComboBox(DesignAuthMode.entries.toTypedArray()).apply {
        renderer = textListCellRenderer("") { it.label }
    }
    private val usernameField = JBTextField()
    private val passwordField = JBPasswordField()
    private val tokenField = JBPasswordField()
    private val testButton = JButton("Test Connection")
    private val status = JBLabel()
    private val saveAnywayHint = JBLabel(FormStatus.html("A failed test does not stop you saving.")).apply {
        foreground = JBColor.GRAY
        isVisible = false
    }

    private lateinit var basicRow: Row
    private lateinit var tokenRow: Row

    /** Guards the listeners while fields are filled programmatically. */
    private var populating = false

    private var disposed = false

    /** The connection being edited; null while nothing is selected. */
    private var current: ConnectionsDraft.Conn? = null

    /**
     * What has been typed, per connection, and what the keychain had — **both keyed by connection id**.
     *
     * A single "current connection" pair was not enough and lost data: editing DEV's password, clicking
     * QA, then pressing Apply saved only QA, because by then the fields no longer held DEV's. One
     * widget serving many connections has to remember per connection.
     */
    private val typed = LinkedHashMap<String, Secrets>()

    private val loaded = HashMap<String, Secrets>()

    val component: JComponent = panel {
        row {
            comment(
                "Used by \"Pull from Flowable Design\". Authenticate with a Design username/password or a " +
                    "personal access token — \"Create Token…\" mints one, so no password has to be kept. " +
                    "Credentials go to the IDE password safe, never into a file.",
            )
        }
        // resizableColumn() throughout: align(FILL) alone leaves a column at its minimum width, which
        // renders a URL field about as wide as the word "http:".
        row("Server URL:") { cell(baseUrlField).align(AlignX.FILL).resizableColumn() }
        row("Authentication:") { cell(authModeCombo) }
        basicRow = row("Username:") {
            cell(usernameField).align(AlignX.FILL).resizableColumn()
            label("Password:")
            cell(passwordField).align(AlignX.FILL).resizableColumn()
        }
        tokenRow = row("Access token:") {
            cell(tokenField).align(AlignX.FILL).resizableColumn()
            button("Create Token…") { createToken() }
            link("Manage in Design…") { openTokenManagement() }
        }
        // The status gets a row of its own: sharing one with the button let a long message drive the
        // column's width, which pushed every field past the edge of the dialog.
        row("") { cell(testButton) }
        row("") { cell(status) }
        row("") { cell(saveAnywayHint) }
    }

    init {
        testButton.addActionListener { testConnection() }
        // Switching mode must not fire a round-trip: a half-pasted token would only produce 401 noise.
        authModeCombo.addActionListener {
            if (!populating) {
                updateAuthRows()
                status.text = FormStatus.html("")
            }
        }
    }

    // ---- the editor's contract ----------------------------------------------------------------

    /** Show [conn], keeping whatever was typed for the connection being left. */
    fun load(conn: ConnectionsDraft.Conn) {
        flush()
        current = conn
        populating = true
        try {
            baseUrlField.text = conn.baseUrl
            authModeCombo.selectedItem = conn.authMode
            usernameField.text = conn.username
        } finally {
            populating = false
        }
        updateAuthRows()
        status.text = FormStatus.html("")
        saveAnywayHint.isVisible = false
        prefillSecrets(conn)
    }

    /**
     * Copy what is on screen back into the draft, and remember the typed secrets.
     *
     * Deliberately touches **no UI**: this is reached from `isModified()`, which the Settings dialog
     * polls, and a repaint on that path feeds the poll loop until the page never finishes loading. The
     * URL is normalised into the draft but not written back into the field for the same reason; the
     * baseline it is compared against was normalised too, so the comparison still settles.
     */
    fun flush() {
        val conn = current ?: return
        val normalized = DesignClient.normalizeBaseUrl(baseUrlField.text)
        conn.baseUrl = normalized
        conn.username = usernameField.text.trim()
        conn.authMode = selectedAuthMode()
        typed[conn.id] = Secrets(
            normalized,
            conn.username,
            String(passwordField.password),
            DesignClient.normalizeAccessToken(String(tokenField.password)),
        )
    }

    /** True when any connection's secret was edited, or its URL moved so they need storing again. */
    fun secretsModified(): Boolean {
        flush()
        return typed.any { (id, secrets) -> loaded[id] != secrets }
    }

    /**
     * Writes every changed secret to the PasswordSafe, off the EDT because the OS keychain can block.
     *
     * A changed URL stores under the new key and **leaves the old one alone** — another environment or
     * another project may still point at the old server. Each store logs on failure: a silently dropped
     * save means the next pull asks for a credential the user is certain they already entered.
     */
    fun saveSecrets() {
        flush()
        val toStore = typed.filter { (id, secrets) -> loaded[id] != secrets && secrets.baseUrl.isNotBlank() }
        toStore.forEach { (id, secrets) -> loaded[id] = secrets }
        if (toStore.isEmpty()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            toStore.values.forEach { secrets ->
                if (secrets.username.isNotBlank()) {
                    runCatching { DesignCredentials.save(secrets.baseUrl, secrets.username, secrets.password) }
                        .onFailure {
                            LOG.warn("Could not store the Design password for ${secrets.baseUrl} in the PasswordSafe", it)
                        }
                }
                if (secrets.token.isNotBlank()) {
                    runCatching { DesignCredentials.saveToken(secrets.baseUrl, secrets.token) }
                        .onFailure {
                            LOG.warn("Could not store the Design access token for ${secrets.baseUrl} in the PasswordSafe", it)
                        }
                }
            }
        }
    }

    override fun dispose() {
        disposed = true
    }

    // ---- internals ----------------------------------------------------------------------------

    private fun selectedAuthMode(): DesignAuthMode =
        authModeCombo.selectedItem as? DesignAuthMode ?: DesignAuthMode.BASIC

    /** Only the selected mode's row is visible; both keep their values, so switching is lossless. */
    private fun updateAuthRows() {
        val token = selectedAuthMode() == DesignAuthMode.ACCESS_TOKEN
        basicRow.visible(!token)
        tokenRow.visible(token)
    }

    /** PasswordSafe access can block on the OS keychain, so both secrets are read in one pooled trip. */
    private fun prefillSecrets(conn: ConnectionsDraft.Conn) {
        val normalized = DesignClient.normalizeBaseUrl(conn.baseUrl)
        // Something typed earlier for this connection wins over the keychain: it is newer.
        typed[conn.id]?.let { pending ->
            passwordField.text = pending.password
            tokenField.text = pending.token
            return
        }
        passwordField.text = ""
        tokenField.text = ""
        if (normalized.isBlank()) {
            val empty = Secrets(normalized, conn.username, "", "")
            loaded[conn.id] = empty
            typed[conn.id] = empty
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            // Warn, not debug: a read failure makes the form look like "no credentials saved yet",
            // which invites the user to retype a secret that is in fact already there.
            val credentials = runCatching { DesignCredentials.load(normalized) }
                .onFailure { LOG.warn("Could not read the Design password for $normalized from the PasswordSafe", it) }
                .getOrNull()
            val storedToken = runCatching { DesignCredentials.loadToken(normalized) }
                .onFailure { LOG.warn("Could not read the Design access token for $normalized from the PasswordSafe", it) }
                .getOrNull()
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                val secrets = Secrets(
                    normalized,
                    credentials?.userName?.takeIf { it.isNotBlank() } ?: conn.username,
                    credentials?.getPasswordAsString().orEmpty(),
                    storedToken.orEmpty(),
                )
                loaded[conn.id] = secrets
                typed[conn.id] = secrets
                if (current?.id != conn.id) return@invokeLater   // the user moved on while we read
                if (usernameField.text.isBlank()) usernameField.text = secrets.username
                if (passwordField.password.isEmpty()) passwordField.text = secrets.password
                if (tokenField.password.isEmpty()) tokenField.text = secrets.token
            }, ModalityState.any())
        }
    }

    /** The connection as typed — deliberately the fields, not the draft, so it tests unsaved edits. */
    private fun currentConnection(): DesignClient.Connection? {
        val baseUrl = DesignClient.normalizeBaseUrl(baseUrlField.text)
        if (baseUrl.isBlank()) {
            showError("Enter the Design server URL first")
            return null
        }
        val auth = when (selectedAuthMode()) {
            DesignAuthMode.BASIC -> {
                if (usernameField.text.isBlank()) {
                    showError("Enter a username first")
                    return null
                }
                DesignClient.Auth.Basic(usernameField.text.trim(), String(passwordField.password))
            }
            DesignAuthMode.ACCESS_TOKEN -> {
                val token = DesignClient.normalizeAccessToken(String(tokenField.password))
                if (token.isBlank()) {
                    showError("Paste a Flowable Design access token first")
                    return null
                }
                DesignClient.Auth.Token(token)
            }
        }
        return DesignClient.Connection(baseUrl, auth)
    }

    /**
     * Lists the server's workspaces and reports what came back. A list is the cheapest call that proves
     * URL, credentials *and* permissions at once, and its count is the useful part of the answer:
     * "connected, but you can see no workspaces" is a different problem from "connected".
     */
    private fun testConnection() {
        val conn = currentConnection() ?: return
        testButton.isEnabled = false
        status.foreground = JBColor.foreground()
        status.text = FormStatus.html("Connecting…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = DesignClient.listWorkspaces(conn)
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                testButton.isEnabled = true
                when (result) {
                    is DesignClient.Result.Success -> {
                        status.foreground = JBColor.foreground()
                        saveAnywayHint.isVisible = false
                        status.text = FormStatus.html(
                            if (result.value.isEmpty()) "Connected, but no workspaces are visible for this user"
                            else "Connected — ${result.value.size} workspace(s) visible",
                        )
                    }
                    is DesignClient.Result.Failed -> showError(result.message)
                }
            }, ModalityState.any())
        }
    }

    private fun openTokenManagement() {
        val baseUrl = DesignClient.normalizeBaseUrl(baseUrlField.text)
        if (baseUrl.isBlank()) {
            showError("Enter the Design server URL first")
            return
        }
        BrowserUtil.browse(DesignClient.tokenManagementUrl(baseUrl))
    }

    /** Mints a token into the field; the dialog only collects input, the request runs off the EDT. */
    private fun createToken() {
        val baseUrl = DesignClient.normalizeBaseUrl(baseUrlField.text)
        if (baseUrl.isBlank()) {
            showError("Enter the Design server URL first")
            return
        }
        val dialog = DesignCreateTokenDialog(project, baseUrl)
        if (!dialog.showAndGet()) return
        status.foreground = JBColor.foreground()
        status.text = FormStatus.html("Creating access token…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = DesignClient.createAccessToken(
                baseUrl, dialog.username, dialog.password, dialog.tokenName, dialog.validFor,
            )
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                when (result) {
                    is DesignClient.Result.Success -> {
                        tokenField.text = result.value.value
                        populating = true
                        try {
                            authModeCombo.selectedItem = DesignAuthMode.ACCESS_TOKEN
                        } finally {
                            populating = false
                        }
                        updateAuthRows()
                        val expires = result.value.expirationTime?.let { ", expires $it" } ?: ""
                        status.foreground = JBColor.foreground()
                        status.text = FormStatus.html("Access token created$expires — click Apply to store it")
                    }
                    is DesignClient.Result.Failed -> showError(result.message)
                }
            }, ModalityState.any())
        }
    }

    /**
     * A failed test is information, not a verdict on the connection: an app or server that is simply not
     * running yet is the most ordinary reason to see one, and being unable to save the URL until it
     * starts would be absurd. The hint says so, because a red line reads like a block.
     */
    private fun showError(message: String) {
        status.foreground = JBColor.RED
        status.text = FormStatus.html(message)
        saveAnywayHint.isVisible = true
    }
}
