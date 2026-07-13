package com.flowable.atlas.settings

import com.flowable.atlas.expr.inspect.InspectConnectionDetector
import com.flowable.atlas.expr.inspect.InspectCredentials
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The Flowable Inspect connection editor, embedded in Settings → Tools → Flowable Atlas →
 * Connections next to the Design editor ([DesignConnectionPanel]): the base URL + basic-auth
 * credentials of the running app the Expression Playground evaluates against ("Evaluate Against App").
 * Base URL and username persist in the VCS-shared project settings ([FlowableAtlasProjectSettings]);
 * the password goes to the IDE PasswordSafe ([InspectCredentials], keyed by base URL) — the same
 * store the playground reads/writes, so a password typed here shows up there and vice versa.
 * "Detect from project" pre-fills the fields from the project's Spring config. Keychain and index
 * access stay off the EDT.
 */
class InspectConnectionPanel(private val project: Project) : JPanel(), Disposable {

    private val settings = FlowableAtlasProjectSettings.getInstance(project)

    private val baseUrlField = JBTextField(30)
    private val usernameField = JBTextField(12)
    private val passwordField = JBPasswordField().apply { columns = 12 }
    private val detectButton = JButton("Detect from project")
    private val status = JBLabel()

    /** Password as loaded from the PasswordSafe, to detect modification without a keychain read. */
    @Volatile
    private var loadedPassword: String = ""

    private var disposed = false

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        detectButton.addActionListener { detectFromProject() }
        add(row(JBLabel("App base URL:"), baseUrlField))
        add(row(JBLabel("Username:"), usernameField, JBLabel("  Password:"), passwordField))
        add(row(detectButton))
        add(row(status))
    }

    private fun row(vararg parts: JComponent): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { parts.forEach { add(it) } }

    // ---- Configurable contract, driven by the surrounding page ------------------------------

    fun reset() {
        baseUrlField.text = settings.inspectBaseUrl
        usernameField.text = settings.inspectUsername
        status.text = ""
        prefillPassword()
    }

    fun isModified(): Boolean =
        baseUrlField.text.trim() != settings.inspectBaseUrl ||
            usernameField.text.trim() != settings.inspectUsername ||
            String(passwordField.password) != loadedPassword

    fun apply() {
        val baseUrl = baseUrlField.text.trim()
        val username = usernameField.text.trim()
        val password = String(passwordField.password)
        settings.inspectBaseUrl = baseUrl
        settings.inspectUsername = username
        loadedPassword = password
        if (baseUrl.isNotBlank()) {
            // PasswordSafe can block on the OS keychain — save off the EDT (same as the Design panel).
            ApplicationManager.getApplication().executeOnPooledThread {
                runCatching { InspectCredentials.save(baseUrl, username, password) }
            }
        }
    }

    override fun dispose() {
        disposed = true
    }

    // ---- population --------------------------------------------------------------------------

    /** PasswordSafe access can block on the OS keychain, so prefill runs on a pooled thread. */
    private fun prefillPassword() {
        loadedPassword = ""
        passwordField.text = ""
        val baseUrl = settings.inspectBaseUrl
        if (baseUrl.isBlank()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val credentials = runCatching { InspectCredentials.load(baseUrl) }.getOrNull() ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                loadedPassword = credentials.getPasswordAsString().orEmpty()
                if (passwordField.password.isEmpty()) passwordField.text = loadedPassword
                if (usernameField.text.isBlank()) credentials.userName?.let { usernameField.text = it }
            }, ModalityState.any())
        }
    }

    /**
     * Fill the fields from the project's Spring config. Detection queries the filename index, so it
     * runs on a pooled thread (never the EDT) and applies its result on the UI thread.
     */
    private fun detectFromProject() {
        detectButton.isEnabled = false
        status.foreground = JBColor.foreground()
        status.text = "Reading project config…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val c = runCatching { InspectConnectionDetector.detect(project) }.getOrNull()
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                detectButton.isEnabled = true
                if (c == null || !c.hasAny) {
                    status.text = "No Flowable app config found in the project."
                    return@invokeLater
                }
                c.baseUrl?.takeIf { it.isNotBlank() }?.let { baseUrlField.text = it }
                c.username?.takeIf { it.isNotBlank() }?.let { usernameField.text = it }
                c.password?.takeIf { it.isNotBlank() }?.let { passwordField.text = it }
                status.text = "Detected: ${c.baseUrl ?: "(no base URL)"}" +
                    (c.username?.let { " · user '$it'" } ?: "") +
                    (if (c.password != null) " · password from dev config" else "")
            }, ModalityState.any())
        }
    }
}
