package com.flowable.atlas.settings.connections

import com.flowable.atlas.design.DesignClient
import com.flowable.atlas.design.DesignCreateTokenDialog
import com.flowable.atlas.environment.BaseUrls
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.environment.auth.AtlasCredentials
import com.flowable.atlas.environment.auth.AuthContext
import com.flowable.atlas.environment.auth.AuthMode
import com.flowable.atlas.environment.auth.BrowserSessions
import com.flowable.atlas.environment.auth.BrowserSignInDialog
import com.flowable.atlas.environment.auth.PasteSessionDialog
import com.flowable.atlas.expr.inspect.InspectClient
import com.flowable.atlas.expr.inspect.InspectConnectionDetector
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
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.RowsRange
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.jcef.JBCefApp
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * One form for **every Flowable server Atlas signs in to** — Design and the running app.
 *
 * There were two, and they had drifted into teaching different things about the same product. Design
 * offered a username/password or an access token; the app offered a username/password, an embedded
 * browser login and a pasted browser session. Neither list was a subset of the other, so a user who
 * had learnt one page arrived at the other and found controls that were missing and controls that were
 * new — with nothing on either page explaining why.
 *
 * Worse than untidy, it left a real hole: Design's answer for an identity provider is an access token,
 * and *minting* one ([DesignCreateTokenDialog]) needs the basic auth that OAuth2 switches off. So a
 * Design behind SSO could only be reached with a token fetched by hand from the Design UI, and the
 * browser-session route that would have solved it existed a few classes away, wired to the other half
 * of the plugin.
 *
 * ### What the two kinds still do differently
 *
 * Only what is genuinely different about the servers, and never about the auth:
 *  - the explanatory line and the URL placeholder;
 *  - *Test Connection* — a workspace list against Design, an evaluation probe against the app;
 *  - *Create Token…* and *Manage in Design…*, which exist because Design mints tokens and the app
 *    does not;
 *  - *Detect from Project*, which reads a Spring config that only describes the running app.
 *
 * ### What is preserved from the two it replaces
 *
 * The hard-won async behaviour, which was the real asset in `DesignConnectionForm`: both secrets are
 * read in **one** off-EDT keychain trip; both credential rows stay in the layout with only the selected
 * one visible, so switching mode loses neither secret (mirroring the two separate keychain records); a
 * [populating] flag keeps programmatic combo changes from firing listeners; every async callback checks
 * [disposed] before touching Swing; and [flush] touches no UI, because it runs on the `isModified()`
 * path the Settings dialog polls.
 */
class ServerConnectionForm(private val project: Project, private val kind: ConnectionKind) : Disposable {

    private val LOG = logger<ServerConnectionForm>()

    /** One connection's secrets as the form holds them. A value type, so "changed?" is `!=`. */
    private data class Secrets(
        val baseUrl: String = "",
        val username: String = "",
        val password: String = "",
        val token: String = "",
    )

    private val baseUrlField = JBTextField().apply { emptyText.text = placeholder() }
    private val authModeCombo = JComboBox(AuthMode.entries.toTypedArray()).apply {
        renderer = textListCellRenderer("") { it.label }
    }
    private val usernameField = JBTextField()
    private val passwordField = JBPasswordField()
    private val tokenField = JBPasswordField()
    private val detectButton = JButton("Detect from Project")
    private val testButton = JButton("Test Connection")
    private val sessionStatus = JBLabel().apply { foreground = JBColor.GRAY }
    private val status = JBLabel()
    private val saveAnywayHint = JBLabel(FormStatus.html("A failed test does not stop you saving.")).apply {
        foreground = JBColor.GRAY
        isVisible = false
    }
    private val sharedNote = JBLabel(FormStatus.html(SHARED_NOTE)).apply { foreground = JBColor.GRAY }

    private lateinit var sharedRow: Row
    private lateinit var basicRows: RowsRange
    private lateinit var tokenRows: RowsRange

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
        row { comment(explanation()) }
        sharedRow = row("") { cell(sharedNote) }
        // Two things, and both are needed. resizableColumn(), because align(FILL) alone leaves the
        // column at its minimum width and a URL field renders about as wide as the word "http:". And
        // columns(), because a JTextField with no column count reports its *text* as its preferred —
        // and therefore minimum — width, so a long URL made this page demand more room than the
        // settings dialog has and pushed the fields past its edge.
        row("Server URL:") {
            cell(baseUrlField).columns(COLUMNS_MEDIUM).align(AlignX.FILL).resizableColumn()
            if (kind == ConnectionKind.WORK) cell(detectButton)
        }
        row("Authentication:") { cell(authModeCombo) }
        // A row per field rather than username and password side by side: two fields and a label in one
        // row means the page cannot be narrower than both of them at once, and a third of its width
        // already belongs to the environment tree.
        basicRows = rowsRange {
            row("Username:") { cell(usernameField).columns(COLUMNS_MEDIUM).align(AlignX.FILL).resizableColumn() }
            row("Password:") { cell(passwordField).columns(COLUMNS_MEDIUM).align(AlignX.FILL).resizableColumn() }
        }
        tokenRows = rowsRange {
            row("Access token:") {
                cell(tokenField).columns(COLUMNS_MEDIUM).align(AlignX.FILL).resizableColumn()
            }
            if (kind == ConnectionKind.DESIGN) {
                row("") {
                    button("Create Token…") { createToken() }
                    link("Manage in Design…") { openTokenManagement() }
                }
                row("") {
                    // Said here because the button cannot say it by failing: minting a token is itself a
                    // basic-auth call, so on the very server where a token is the only way in, this is
                    // the one route that does not work.
                    comment(
                        "Creating a token signs in with the username and password above. A server behind " +
                            "SSO has those switched off — create the token in Design, or sign in via the " +
                            "browser below.",
                    )
                }
            }
        }
        // Available for both kinds now. An identity provider in front of a Flowable server is not a
        // property of which Flowable server it is.
        row("") {
            cell(testButton)
            link("Sign in via Browser…") { signIn() }
            link("Paste Session…") { pasteSession() }
        }
        row("") { cell(sessionStatus) }
        row("") {
            comment(
                "For SSO/OAuth2-fronted servers, where a username and password cannot pass the login. " +
                    "Both reuse your browser session, for this IDE session only. If the embedded sign-in " +
                    "is blocked by your IdP, use \"Paste Session\" (DevTools → Copy as cURL). Combine it " +
                    "with the credentials above when Flowable also wants them behind the SSO layer.",
            )
        }
        // The status gets a row of its own: sharing one with the button let a long message drive the
        // column's width, which pushed every field past the edge of the dialog.
        row("") { cell(status) }
        row("") { cell(saveAnywayHint) }
    }

    init {
        detectButton.addActionListener { detectFromProject() }
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
        // A shared connection's URL and auth mode belong to the project's committed file; the
        // credentials below them do not, and are the whole reason this form still opens for one.
        sharedRow.visible(conn.shared)
        baseUrlField.isEditable = !conn.shared
        authModeCombo.isEnabled = !conn.shared
        detectButton.isEnabled = !conn.shared
        updateAuthRows()
        status.text = FormStatus.html("")
        saveAnywayHint.isVisible = false
        updateSessionStatus()
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
        // A shared connection keeps the file's URL and mode whatever is on screen — the fields are not
        // editable, and reading them back would be one accident away from a local override nobody asked
        // for. The username still travels: it is this developer's, and it keys their keychain record.
        val normalized = if (conn.shared) conn.baseUrl else BaseUrls.normalize(kind, baseUrlField.text)
        conn.baseUrl = normalized
        conn.username = usernameField.text.trim()
        if (!conn.shared) conn.authMode = selectedAuthMode()
        typed[conn.id] = Secrets(
            normalized,
            conn.username,
            String(passwordField.password),
            AuthContext.normalizeToken(String(tokenField.password)),
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
                if (secrets.username.isNotBlank() || secrets.password.isNotEmpty()) {
                    runCatching { AtlasCredentials.save(secrets.baseUrl, secrets.username, secrets.password) }
                        .onFailure {
                            LOG.warn("Could not store the password for ${secrets.baseUrl} in the PasswordSafe", it)
                        }
                }
                if (secrets.token.isNotBlank()) {
                    runCatching { AtlasCredentials.saveToken(secrets.baseUrl, secrets.token) }
                        .onFailure {
                            LOG.warn("Could not store the access token for ${secrets.baseUrl} in the PasswordSafe", it)
                        }
                }
            }
        }
    }

    override fun dispose() {
        disposed = true
    }

    // ---- internals ----------------------------------------------------------------------------

    private fun explanation(): String = when (kind) {
        ConnectionKind.DESIGN ->
            "Used by \"Pull from Flowable Design\". Authenticate with a username and password or a " +
                "personal access token — and, for a server behind an identity provider, with your " +
                "browser session. Credentials go to the IDE password safe, never into a file."
        else ->
            "A running Flowable app the Expression Playground evaluates backend expressions against " +
                "(\"Evaluate Against App\", through the Inspect REST API). Authenticate with a username " +
                "and password or an access token — and, behind an identity provider, with your browser " +
                "session. Credentials go to the IDE password safe, never into a file."
    }

    private fun placeholder(): String = when (kind) {
        ConnectionKind.DESIGN -> "http://design.example.com/flowable-design"
        else -> "http://localhost:8080"
    }

    private fun selectedAuthMode(): AuthMode = authModeCombo.selectedItem as? AuthMode ?: AuthMode.BASIC

    /** Only the selected mode's rows are visible; both keep their values, so switching is lossless. */
    private fun updateAuthRows() {
        val token = selectedAuthMode() == AuthMode.ACCESS_TOKEN
        basicRows.visible(!token)
        tokenRows.visible(token)
    }

    /** PasswordSafe access can block on the OS keychain, so both secrets are read in one pooled trip. */
    private fun prefillSecrets(conn: ConnectionsDraft.Conn) {
        val normalized = BaseUrls.normalize(kind, conn.baseUrl)
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
            val credentials = runCatching { AtlasCredentials.load(normalized) }
                .onFailure { LOG.warn("Could not read the password for $normalized from the PasswordSafe", it) }
                .getOrNull()
            val storedToken = runCatching { AtlasCredentials.loadToken(normalized) }
                .onFailure { LOG.warn("Could not read the access token for $normalized from the PasswordSafe", it) }
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

    /** What is on screen, not what is stored — so *Test Connection* proves the edits being made. */
    private fun typedAuth(baseUrl: String): AuthContext {
        val session = BrowserSessions.get(baseUrl).orEmpty()
        return when (selectedAuthMode()) {
            AuthMode.BASIC -> AuthContext.basic(usernameField.text.trim(), String(passwordField.password), session)
            AuthMode.ACCESS_TOKEN -> AuthContext.token(String(tokenField.password), session)
        }
    }

    private fun updateSessionStatus() {
        val baseUrl = baseUrlField.text.trim()
        // A pure in-memory lookup, so it costs nothing to keep current.
        val headers = baseUrl.takeIf { it.isNotBlank() }?.let { BrowserSessions.get(it) }
        sessionStatus.text = if (headers == null) {
            "Browser session: none"
        } else {
            "Browser session: captured (${headers.keys.joinToString(", ")}) for this IDE session"
        }
    }

    /** For a server behind an identity provider: log in through the embedded browser, keep the cookie. */
    private fun signIn() {
        val baseUrl = baseUrlField.text.trim()
        if (baseUrl.isBlank()) {
            showError("Enter the server URL first, then sign in.")
            return
        }
        if (!JBCefApp.isSupported()) {
            showError("The embedded browser (JCEF) isn't available in this IDE, so browser sign-in can't run.")
            return
        }
        val dialog = BrowserSignInDialog(project, baseUrl)
        if (!dialog.showAndGet()) return
        val cookie = dialog.harvestedCookie
        if (cookie.isNullOrBlank()) {
            showError("No session cookie was captured — make sure the login completed, then try again.")
        } else {
            BrowserSessions.set(baseUrl, mapOf("Cookie" to cookie))
            status.foreground = JBColor.foreground()
            status.text = FormStatus.html("Signed in — session captured for this IDE session.")
        }
        updateSessionStatus()
    }

    /** The reliable route when the embedded login is blocked: DevTools → Copy as cURL. */
    private fun pasteSession() {
        val dialog = PasteSessionDialog(project)
        if (!dialog.showAndGet()) return
        val parsed = dialog.parsed
        if (!parsed.hasAny) {
            showError("No session headers found in the pasted text — copy a request as cURL (or its Cookie header).")
            return
        }
        if (baseUrlField.text.isBlank()) parsed.baseUrl?.let { baseUrlField.text = it }
        val baseUrl = baseUrlField.text.trim()
        if (baseUrl.isBlank()) {
            showError("Enter the server URL first, then paste the session.")
            return
        }
        BrowserSessions.set(baseUrl, parsed.headers)
        status.foreground = JBColor.foreground()
        status.text = FormStatus.html("Session captured (${parsed.headers.keys.joinToString(", ")}) for this IDE session.")
        updateSessionStatus()
    }

    /**
     * Proves the connection with the cheapest call that exercises URL, credentials *and* permissions.
     *
     * The call differs per kind and its answer does too — a Design workspace list can succeed and still
     * be empty, which is a different problem from "connected".
     */
    private fun testConnection() {
        val baseUrl = BaseUrls.normalize(kind, baseUrlField.text)
        if (baseUrl.isBlank()) {
            showError("Enter the server URL first")
            return
        }
        val auth = typedAuth(baseUrl)
        if (auth.isEmpty) {
            showError(
                if (selectedAuthMode() == AuthMode.ACCESS_TOKEN) "Paste an access token first"
                else "Enter a username first, or sign in via the browser",
            )
            return
        }
        testButton.isEnabled = false
        status.foreground = JBColor.foreground()
        status.text = FormStatus.html("Connecting…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val message = when (kind) {
                ConnectionKind.DESIGN -> when (val result = DesignClient.listWorkspaces(DesignClient.Connection(baseUrl, auth))) {
                    is DesignClient.Result.Success ->
                        if (result.value.isEmpty()) "Connected, but no workspaces are visible for this user" to false
                        else "Connected — ${result.value.size} workspace(s) visible" to false
                    is DesignClient.Result.Failed -> result.message to true
                }
                else -> when (val outcome = InspectClient.probe(baseUrl, auth)) {
                    is InspectClient.Outcome.Evaluated -> "Reachable — the app answered" to false
                    is InspectClient.Outcome.Failed -> outcome.message to true
                }
            }
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                testButton.isEnabled = true
                val (text, failed) = message
                if (failed) {
                    showError(text)
                } else {
                    status.foreground = JBColor.foreground()
                    status.text = FormStatus.html(text)
                    saveAnywayHint.isVisible = false
                }
            }, ModalityState.any())
        }
    }

    /**
     * Fill the fields from the project's Spring config. Detection queries the filename index, so it
     * runs on a pooled thread and applies its result on the UI thread.
     */
    private fun detectFromProject() {
        detectButton.isEnabled = false
        status.foreground = JBColor.foreground()
        status.text = FormStatus.html("Reading project config…")
        ApplicationManager.getApplication().executeOnPooledThread {
            // The form already says "no config found"; the log distinguishes "really nothing there"
            // from "the detector blew up on a malformed config".
            val detected = runCatching { InspectConnectionDetector.detect(project) }
                .onFailure { LOG.warn("Inspect connection detection failed", it) }
                .getOrNull()
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                detectButton.isEnabled = true
                if (detected == null || !detected.hasAny) {
                    status.text = FormStatus.html("No Flowable app config found in the project.")
                    return@invokeLater
                }
                detected.baseUrl?.takeIf { it.isNotBlank() }?.let { baseUrlField.text = it }
                detected.username?.takeIf { it.isNotBlank() }?.let { usernameField.text = it }
                detected.password?.takeIf { it.isNotBlank() }?.let { passwordField.text = it }
                status.text = FormStatus.html(
                    "Detected: ${detected.baseUrl ?: "(no base URL)"}" +
                        (detected.username?.let { " · user '$it'" } ?: "") +
                        (if (detected.password != null) " · password from dev config" else ""),
                )
                updateSessionStatus()
            }, ModalityState.any())
        }
    }

    private fun openTokenManagement() {
        val baseUrl = DesignClient.normalizeBaseUrl(baseUrlField.text)
        if (baseUrl.isBlank()) {
            showError("Enter the server URL first")
            return
        }
        BrowserUtil.browse(DesignClient.tokenManagementUrl(baseUrl))
    }

    /** Mints a token into the field; the dialog only collects input, the request runs off the EDT. */
    private fun createToken() {
        val baseUrl = DesignClient.normalizeBaseUrl(baseUrlField.text)
        if (baseUrl.isBlank()) {
            showError("Enter the server URL first")
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
                            authModeCombo.selectedItem = AuthMode.ACCESS_TOKEN
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
     * A failed test is information, not a verdict on the connection: a server that is simply not
     * running yet is the most ordinary reason to see one, and being unable to save the URL until it
     * starts would be absurd. The hint says so, because a red line reads like a block.
     */
    private fun showError(message: String) {
        status.foreground = JBColor.RED
        status.text = FormStatus.html(message)
        saveAnywayHint.isVisible = true
    }

    private companion object {
        const val SHARED_NOTE =
            "Defined by this project — the URL comes from the committed " +
                "<code>.idea/flowable-environments.xml</code>, so everyone who clones the repository has it. " +
                "Your username and password are yours alone and stay in the IDE password safe."
    }
}
