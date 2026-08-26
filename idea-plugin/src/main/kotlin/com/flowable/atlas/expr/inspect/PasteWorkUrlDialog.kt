package com.flowable.atlas.expr.inspect

import com.flowable.atlas.environment.AtlasCatalog
import com.flowable.atlas.environment.AtlasConnection
import com.flowable.atlas.environment.AtlasEnvironments
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.environment.WorkConnectionMatcher
import com.flowable.atlas.environment.auth.AtlasCredentials
import com.flowable.atlas.environment.auth.AuthContext
import com.flowable.atlas.environment.auth.BrowserSessions
import com.flowable.atlas.environment.auth.BrowserSignInDialog
import com.flowable.atlas.environment.auth.PasteSessionDialog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Paste a Flowable Work link and get everything the playground needs out of it: which app, which
 * instance, and the credentials to reach it — checked before the dialog closes.
 *
 * It deliberately **creates nothing**. A link from a colleague, a one-off look at a stage you do not
 * work against, an app you will never open again: none of those should leave an environment behind, and
 * being made to name one before you can evaluate is a toll on the common case. An environment is
 * something you decide to have, in *Settings → Environments*; this is a place to go once.
 *
 * Because it saves nothing, the credentials for an unknown app are kept for this IDE session only —
 * in [InspectSession], the same in-memory store a browser sign-in uses, as a basic `Authorization`
 * header. Nothing typed here reaches the disk.
 *
 * It exists because the playground's card was carrying all of this in the open: a URL field, a line
 * explaining what a URL does, a *Save as environment…* link that only sometimes applied, and an
 * "unsaved" state in the environment picker. Four controls and three sentences for something that
 * happens in one gesture.
 */
class PasteWorkUrlDialog(private val project: Project) : DialogWrapper(project) {

    /** What the dialog resolved to. [connectionId] is null when the link named an app you have not defined. */
    data class Result(
        val connectionId: String?,
        val baseUrl: String,
        val username: String,
        val password: String,
        val parsed: WorkUrlParser.Parsed,
    )

    private val urlField = JBTextField(46)
    private val recognised = JBLabel()
    private val usernameField = JBTextField(16)
    private val passwordField = JBPasswordField()
    private val testButton = JButton("Test Connection")
    private val testStatus = JBLabel()
    private val sessionStatus = JBLabel().apply { foreground = JBColor.GRAY }

    private lateinit var credentialRows: com.intellij.ui.dsl.builder.RowsRange
    private lateinit var recognisedRow: Row

    private var parsed: WorkUrlParser.Parsed = WorkUrlParser.parse("")
    private var match: AtlasConnection? = null

    /** So [reparse] only re-packs the dialog when the extra fields come or go. */
    private var wasShowingNewEnvironment = false

    /** Which connection the credential fields were filled from, so a re-parse does not refill them. */
    private var prefilledFor: String? = null

    init {
        title = "Evaluate Against a Work URL"
        setOKButtonText("Use")
        init()
        // Parsed straight from the document listener, with no alarm in between. An alarm created
        // before the dialog is shown captures the *non-modal* modality state, so its runnable sits in
        // the queue until the dialog closes — the field looked unparsed however carefully you pasted,
        // and OK stayed disabled. Parsing is a pure string operation; there is nothing to debounce.
        urlField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = reparse()
            override fun removeUpdate(e: DocumentEvent) = reparse()
            override fun changedUpdate(e: DocumentEvent) = reparse()
        })
        testButton.addActionListener { testConnection() }
        reparse()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Work URL:") {
            cell(urlField).align(AlignX.FILL).resizableColumn()
                .comment("Copy the address of a case, process or task from Flowable Work and paste it here.")
        }
        recognisedRow = row("") { cell(recognised) }
        // Shown for a *known* app too, prefilled from what is stored. Hiding them once the app was
        // recognised looked tidy and was a dead end: a saved password that is empty or wrong could only
        // be corrected in Settings, and the only clue was a 401 from the next evaluation.
        credentialRows = rowsRange {
            row("Username:") { cell(usernameField).align(AlignX.FILL).resizableColumn() }
            row("Password:") { cell(passwordField).align(AlignX.FILL).resizableColumn() }
            // Basic auth is the common case, so its two fields are the ones in the open. The other
            // route is a browser session — for an app behind an identity provider, where a username and
            // password cannot pass the login at all, and equally for a bearer token pasted from a cURL.
            // They are links rather than a mode selector because they are not alternatives: an
            // SSO-fronted Flowable can want the session *and* basic auth behind it, and the request
            // sends whatever is there.
            row("") {
                cell(testButton)
                link("Sign in via Browser…") { signIn() }
                link("Paste Session…") { pasteSession() }
            }
            row("") { cell(sessionStatus) }
            row("") { cell(testStatus) }
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = urlField

    override fun doValidate(): ValidationInfo? = when {
        urlField.text.isBlank() -> ValidationInfo("Paste a link from Flowable Work", urlField)
        // An instance id is *not* required: a link to the app alone is a perfectly good way to point the
        // playground at it, and the instance id has its own field there.
        parsed.baseUrl == null -> ValidationInfo("That does not look like a Flowable Work address", urlField)
        else -> null
    }

    /** What was resolved. Creates nothing — see the class note. */
    fun result(): Result = Result(
        connectionId = match?.id,
        baseUrl = parsed.baseUrl.orEmpty(),
        username = usernameField.text.trim(),
        password = String(passwordField.password),
        parsed = parsed,
    )

    // ---- live parsing -------------------------------------------------------------------------

    private fun reparse() {
        parsed = WorkUrlParser.parse(urlField.text)
        match = WorkConnectionMatcher.match(
            parsed.baseUrl,
            AtlasCatalog.connections(project, ConnectionKind.WORK),
        )
        val known = match
        recognised.foreground = JBColor.foreground()
        recognised.text = when {
            parsed.baseUrl == null -> ""
            known != null -> "<html>${known.environmentName} · ${scopeText()}</html>"
            // "This session" rather than "new app": it says what will happen, which is nothing lasting.
            else -> "<html><b>This session only</b> · ${scopeText()}</html>"
        }
        recognisedRow.visible(parsed.baseUrl != null)
        val showsNewEnvironment = parsed.baseUrl != null && known == null
        credentialRows.visible(parsed.baseUrl != null)
        if (known != null && known.id != prefilledFor) prefillCredentials(known)
        testStatus.text = ""
        updateSessionStatus()
        // Only when the extra fields actually appeared or disappeared: resizing on every keystroke
        // makes a dialog jump under the cursor.
        if (showsNewEnvironment != wasShowingNewEnvironment) {
            wasShowingNewEnvironment = showsNewEnvironment
            pack()
        }
    }

    /**
     * Fills in what is already stored for a recognised app, so the fields show the truth and a wrong
     * password can be corrected here. The keychain can block, so the read happens off the EDT.
     */
    private fun prefillCredentials(connection: AtlasConnection) {
        prefilledFor = connection.id
        usernameField.text = connection.username
        passwordField.text = ""
        val baseUrl = connection.baseUrl
        ApplicationManager.getApplication().executeOnPooledThread {
            val stored = runCatching { AtlasCredentials.load(baseUrl) }.getOrNull()
            ApplicationManager.getApplication().invokeLater({
                if (prefilledFor != connection.id) return@invokeLater   // the URL changed while we read
                if (usernameField.text.isBlank()) usernameField.text = stored?.userName.orEmpty()
                if (passwordField.password.isEmpty()) passwordField.text = stored?.getPasswordAsString().orEmpty()
            }, ModalityState.stateForComponent(usernameField))
        }
    }

    /** `case CAS-1` — the instance the link points at, or just the app when it carries none. */
    private fun scopeText(): String {
        val id = parsed.scopeId ?: return "no instance in the link"
        val type = when (parsed.scopeType) {
            InspectClient.ScopeType.CMMN -> "case"
            InspectClient.ScopeType.BPMN -> "process"
            InspectClient.ScopeType.TASK -> "task"
            null -> "instance"
        }
        return "$type $id"
    }

    /** For an app behind an identity provider: log in through the embedded browser and keep the cookie. */
    private fun signIn() {
        val baseUrl = parsed.baseUrl ?: return
        if (!JBCefApp.isSupported()) {
            testStatus.foreground = JBColor.RED
            testStatus.text = "The embedded browser (JCEF) isn't available in this IDE."
            return
        }
        val dialog = BrowserSignInDialog(project, baseUrl)
        if (!dialog.showAndGet()) return
        val cookie = dialog.harvestedCookie
        if (cookie.isNullOrBlank()) {
            testStatus.foreground = JBColor.RED
            testStatus.text = "No session cookie was captured — make sure the login completed."
        } else {
            BrowserSessions.set(baseUrl, mapOf("Cookie" to cookie))
        }
        updateSessionStatus()
    }

    /** The reliable route when the embedded login is blocked: DevTools → Copy as cURL. */
    private fun pasteSession() {
        val baseUrl = parsed.baseUrl ?: return
        val dialog = PasteSessionDialog(project)
        if (!dialog.showAndGet()) return
        val captured = dialog.parsed
        if (!captured.hasAny) {
            testStatus.foreground = JBColor.RED
            testStatus.text = "No session headers found in the pasted text."
            return
        }
        BrowserSessions.set(baseUrl, captured.headers)
        updateSessionStatus()
    }

    /** A pure in-memory lookup, so it costs nothing to keep current. */
    private fun updateSessionStatus() {
        val baseUrl = parsed.baseUrl
        val headers = baseUrl?.let { BrowserSessions.get(it) }
        sessionStatus.text = if (headers == null) {
            "Browser session: none"
        } else {
            "Browser session: captured (${headers.keys.joinToString(", ")}) for this IDE session"
        }
    }

    /** Proves the credentials before the dialog closes — the whole reason this is a dialog. */
    private fun testConnection() {
        val baseUrl = parsed.baseUrl ?: return
        val username = usernameField.text.trim()
        val password = String(passwordField.password)
        testButton.isEnabled = false
        testStatus.foreground = JBColor.foreground()
        testStatus.text = "Connecting…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = InspectClient.probe(
                baseUrl, AuthContext.basic(username, password, BrowserSessions.get(baseUrl).orEmpty()),
            )
            ApplicationManager.getApplication().invokeLater({
                testButton.isEnabled = true
                when (outcome) {
                    is InspectClient.Outcome.Evaluated -> {
                        testStatus.foreground = JBColor.foreground()
                        testStatus.text = "Reachable — the app answered"
                    }
                    is InspectClient.Outcome.Failed -> {
                        testStatus.foreground = JBColor.RED
                        testStatus.text = outcome.message
                    }
                }
            }, ModalityState.stateForComponent(testButton))
        }
    }
}
