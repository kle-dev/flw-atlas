package com.flowable.atlas.settings.connections

import com.flowable.atlas.environment.BaseUrls
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.expr.inspect.InspectClient
import com.flowable.atlas.expr.inspect.InspectConnectionDetector
import com.flowable.atlas.expr.inspect.InspectCredentials
import com.flowable.atlas.expr.inspect.InspectPasteSessionDialog
import com.flowable.atlas.expr.inspect.InspectSession
import com.flowable.atlas.expr.inspect.InspectSignInDialog
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
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.jcef.JBCefApp
import javax.swing.JButton
import javax.swing.JComponent

/**
 * The running-app half of one environment: the base URL the Expression Playground evaluates against,
 * how it authenticates, and — for an app behind an identity provider — the browser session to replay.
 *
 * Everything here used to be duplicated: once on the old `InspectConnectionPanel` and once inside the
 * playground's own backend card, which then wrote its copy back into the shared project settings on
 * every evaluation. That is the desync the user reported, and the fix is structural rather than
 * cosmetic: these fields now exist in exactly one place, and the playground shows the choice instead
 * of re-editing it.
 *
 * The SSO controls came along from the playground for the same reason — signing in is configuring a
 * connection, so it belongs beside the connection, not beside the expression being evaluated.
 */
class WorkConnectionForm(private val project: Project) : Disposable {

    private val LOG = logger<WorkConnectionForm>()

    private val baseUrlField = JBTextField().apply {
        emptyText.text = "http://localhost:8080"
    }
    private val usernameField = JBTextField()
    private val passwordField = JBPasswordField()
    private val detectButton = JButton("Detect from Project")
    private val testButton = JButton("Test Connection")
    private val sessionStatus = JBLabel().apply { foreground = JBColor.GRAY }
    private val status = JBLabel()
    private val saveAnywayHint = JBLabel(FormStatus.html("A failed test does not stop you saving.")).apply {
        foreground = JBColor.GRAY
        isVisible = false
    }

    private val sharedNote = JBLabel(FormStatus.html(SHARED_NOTE)).apply { foreground = JBColor.GRAY }
    private lateinit var sharedRow: com.intellij.ui.dsl.builder.Row

    private var disposed = false

    private var current: ConnectionsDraft.Conn? = null

    /** One connection's secrets as the form holds them. A value type, so "changed?" is `!=`. */
    private data class Secrets(val baseUrl: String = "", val username: String = "", val password: String = "")

    /**
     * Keyed by connection id, both of them: a single "current connection" pair lost data, because
     * editing DEV's password, clicking QA and then pressing Apply saved only QA — by then the fields no
     * longer held DEV's. One widget serving many connections has to remember per connection.
     */
    private val typed = LinkedHashMap<String, Secrets>()

    private val loaded = HashMap<String, Secrets>()

    val component: JComponent = panel {
        row {
            comment(
                "A running Flowable app the Expression Playground evaluates backend expressions against " +
                    "(\"Evaluate Against App\", through the Inspect REST API). The password goes to the IDE " +
                    "password safe, never into a file.",
            )
        }
        sharedRow = row("") { cell(sharedNote) }
        row("App base URL:") {
            // Two things, and both are needed. resizableColumn(), because align(FILL) alone leaves the
            // column at its minimum width and a URL field renders about as wide as the word "http:".
            // And columns(), because a JTextField with no column count reports its *text* as its
            // preferred — and therefore minimum — width, so a long URL made this page demand more room
            // than the settings dialog has and pushed the fields past its edge.
            cell(baseUrlField).columns(COLUMNS_MEDIUM).align(AlignX.FILL).resizableColumn()
            cell(detectButton)
        }
        // A row per field: two fields and a label in one row means the page cannot be narrower than
        // both at once, and a third of its width already belongs to the environment tree.
        row("Username:") { cell(usernameField).columns(COLUMNS_MEDIUM).align(AlignX.FILL).resizableColumn() }
        row("Password:") { cell(passwordField).columns(COLUMNS_MEDIUM).align(AlignX.FILL).resizableColumn() }
        row("") {
            cell(sessionStatus)
            link("Sign in via Browser…") { signIn() }
            link("Paste Session…") { pasteSession() }
        }
        row("") {
            comment(
                "For SSO/OAuth2-fronted apps, where basic auth can't pass. Both reuse your browser session " +
                    "for this IDE session only. If the embedded sign-in is blocked by your IdP, use \"Paste " +
                    "Session\" (DevTools → Copy as cURL). Combine with the username and password above when " +
                    "Flowable also requires basic auth behind the SSO layer.",
            )
        }
        // The status gets a row of its own: sharing one with the button let a long message drive the
        // column's width, which is what pushed every field past the edge of the dialog.
        row("") { cell(testButton) }
        row("") { cell(status) }
        row("") { cell(saveAnywayHint) }
    }

    init {
        detectButton.addActionListener { detectFromProject() }
        testButton.addActionListener { testConnection() }
    }

    // ---- the editor's contract ----------------------------------------------------------------

    fun load(conn: ConnectionsDraft.Conn) {
        flush()                       // keep what was typed for the connection being left
        current = conn
        baseUrlField.text = conn.baseUrl
        usernameField.text = conn.username
        // The URL is the project's; the credentials under it are this developer's.
        sharedRow.visible(conn.shared)
        baseUrlField.isEditable = !conn.shared
        detectButton.isEnabled = !conn.shared
        status.text = FormStatus.html("")
        saveAnywayHint.isVisible = false
        updateSessionStatus()
        prefillPassword(conn)
    }

    /**
     * Copy what is on screen back into the draft, and remember the typed secrets. Touches **no UI**:
     * this runs on the `isModified()` path the Settings dialog polls, and a repaint there feeds the
     * poll loop until the page never finishes loading.
     */
    fun flush() {
        val conn = current ?: return
        // A shared connection keeps the file's URL whatever is on screen; the username is still this
        // developer's and keys their keychain record.
        val normalized =
            if (conn.shared) conn.baseUrl else BaseUrls.normalize(ConnectionKind.WORK, baseUrlField.text)
        conn.baseUrl = normalized
        conn.username = usernameField.text.trim()
        typed[conn.id] = Secrets(normalized, conn.username, String(passwordField.password))
    }

    fun secretsModified(): Boolean {
        flush()
        return typed.any { (id, secrets) -> loaded[id] != secrets }
    }

    /**
     * Stores every changed password under its (possibly new) URL, off the EDT. The old key is left
     * alone — another environment or project may still point at the old server.
     */
    fun saveSecrets() {
        flush()
        val toStore = typed.filter { (id, secrets) -> loaded[id] != secrets && secrets.baseUrl.isNotBlank() }
        toStore.forEach { (id, secrets) -> loaded[id] = secrets }
        if (toStore.isEmpty()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            toStore.values.forEach { secrets ->
                // A silently dropped save means the playground keeps asking for a password the user is
                // certain they already entered.
                runCatching { InspectCredentials.save(secrets.baseUrl, secrets.username, secrets.password) }
                    .onFailure {
                        LOG.warn("Could not store the Inspect password for ${secrets.baseUrl} in the PasswordSafe", it)
                    }
            }
        }
    }

    override fun dispose() {
        disposed = true
    }

    // ---- internals ----------------------------------------------------------------------------

    private fun prefillPassword(conn: ConnectionsDraft.Conn) {
        // Something typed earlier for this connection wins over the keychain: it is newer.
        typed[conn.id]?.let { pending ->
            passwordField.text = pending.password
            return
        }
        passwordField.text = ""
        val baseUrl = conn.baseUrl
        if (baseUrl.isBlank()) {
            val empty = Secrets(baseUrl, conn.username, "")
            loaded[conn.id] = empty
            typed[conn.id] = empty
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val credentials = runCatching { InspectCredentials.load(baseUrl) }
                .onFailure { LOG.warn("Could not read the Inspect password for $baseUrl from the PasswordSafe", it) }
                .getOrNull()
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                val secrets = Secrets(
                    baseUrl,
                    credentials?.userName?.takeIf { it.isNotBlank() } ?: conn.username,
                    credentials?.getPasswordAsString().orEmpty(),
                )
                loaded[conn.id] = secrets
                typed[conn.id] = secrets
                if (current?.id != conn.id) return@invokeLater   // the user moved on while we read
                if (passwordField.password.isEmpty()) passwordField.text = secrets.password
                if (usernameField.text.isBlank()) usernameField.text = secrets.username
            }, ModalityState.any())
        }
    }

    private fun updateSessionStatus() {
        val baseUrl = baseUrlField.text.trim()
        // A pure in-memory lookup, so this costs nothing and can be refreshed as often as it likes.
        sessionStatus.text = if (baseUrl.isNotBlank() && InspectSession.get(baseUrl) != null) {
            "Browser session: captured for this IDE session"
        } else {
            "Browser session: none"
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

    private fun testConnection() {
        val baseUrl = baseUrlField.text.trim()
        if (baseUrl.isBlank()) {
            showError("Enter the app base URL first")
            return
        }
        val username = usernameField.text.trim()
        val password = String(passwordField.password)
        val session = InspectSession.get(baseUrl)
        testButton.isEnabled = false
        status.foreground = JBColor.foreground()
        status.text = FormStatus.html("Connecting…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = InspectClient.probe(baseUrl, username, password, session)
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                testButton.isEnabled = true
                when (outcome) {
                    is InspectClient.Outcome.Evaluated -> {
                        status.foreground = JBColor.foreground()
                        status.text = FormStatus.html("Reachable — the app answered")
                        saveAnywayHint.isVisible = false
                    }
                    is InspectClient.Outcome.Failed -> showError(outcome.message)
                }
            }, ModalityState.any())
        }
    }

    private fun signIn() {
        val baseUrl = baseUrlField.text.trim()
        if (baseUrl.isBlank()) {
            showError("Enter the app base URL first, then sign in.")
            return
        }
        if (!JBCefApp.isSupported()) {
            showError("The embedded browser (JCEF) isn't available in this IDE, so browser sign-in can't run.")
            return
        }
        val dialog = InspectSignInDialog(project, baseUrl)
        if (!dialog.showAndGet()) return
        val cookie = dialog.harvestedCookie
        if (cookie.isNullOrBlank()) {
            showError("No session cookie was captured — make sure the login completed, then try again.")
        } else {
            InspectSession.set(baseUrl, mapOf("Cookie" to cookie))
            status.foreground = JBColor.foreground()
            status.text = FormStatus.html("Signed in — session captured for this IDE session.")
        }
        updateSessionStatus()
    }

    private fun pasteSession() {
        val dialog = InspectPasteSessionDialog(project)
        if (!dialog.showAndGet()) return
        val parsed = dialog.parsed
        if (!parsed.hasAny) {
            showError("No session headers found in the pasted text — copy a request as cURL (or its Cookie header).")
            return
        }
        if (baseUrlField.text.isBlank()) parsed.baseUrl?.let { baseUrlField.text = it }
        val baseUrl = baseUrlField.text.trim()
        if (baseUrl.isBlank()) {
            showError("Enter the app base URL first, then paste the session.")
            return
        }
        InspectSession.set(baseUrl, parsed.headers)
        status.foreground = JBColor.foreground()
        status.text = FormStatus.html("Session captured (${parsed.headers.keys.joinToString(", ")}) for this IDE session.")
        updateSessionStatus()
    }

    private companion object {
        const val SHARED_NOTE = "Defined by this project — the URL comes from the committed " +
                    "<code>.idea/flowable-environments.xml</code>, so everyone who clones the repository has it. " +
                    "Your username and password are yours alone and stay in the IDE password safe."
    }

    /**
     * A failed test is information, not a verdict on the connection: an app that is simply not running
     * yet is the most ordinary reason to see one, and being unable to save the URL until it starts
     * would be absurd. The hint says so, because a red line beside a greyed-looking dialog reads like
     * a block.
     */
    private fun showError(message: String) {
        status.foreground = JBColor.RED
        status.text = FormStatus.html(message)
        saveAnywayHint.isVisible = true
    }
}
