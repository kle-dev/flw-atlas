package com.flowable.atlas.design

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import java.awt.FlowLayout
import java.net.InetAddress
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Collects what [DesignClient.createAccessToken] needs so the user can mint a Design personal access
 * token without leaving the IDE — and, once stored, without a password in the keychain at all.
 *
 * **Input only:** the dialog performs no network or keychain access; [DesignConnectionPanel] runs the
 * call on a pooled thread after OK. The username/password typed here authenticate that one request and
 * are never persisted.
 */
class DesignCreateTokenDialog(project: Project, private val baseUrl: String) : DialogWrapper(project) {

    private val usernameField = JBTextField(20)
    private val passwordField = JBPasswordField().apply { columns = 20 }
    private val nameField = JBTextField(defaultTokenName(), 28)
    private val daysField = JBTextField("365", 6)

    val username: String get() = usernameField.text.trim()
    val password: String get() = String(passwordField.password)
    val tokenName: String get() = nameField.text.trim()

    /** The validity as the ISO-8601 duration Design expects, or null when the field was left blank. */
    val validFor: String? get() = daysField.text.trim().toIntOrNull()?.let { "P${it}D" }

    init {
        title = "Create Flowable Design Access Token"
        setOKButtonText("Create Token")
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(
            JBLabel(
                "<html>Signs in to <b>$baseUrl</b> once with your username/password to create a personal " +
                    "access token, then uses only the token.<br>" +
                    "Design shows a token's value just once, so it is stored right away in the IDE " +
                    "PasswordSafe when you click Apply.<br>" +
                    "If this Design is behind SSO, username/password is disabled there — create the token " +
                    "in Design itself via \"Manage in Design…\" instead.</html>",
            ),
        )
        add(row(JBLabel("Username:"), usernameField, JBLabel("  Password:"), passwordField))
        add(row(JBLabel("Token name:"), nameField))
        add(row(JBLabel("Valid for (days):"), daysField))
    }

    private fun row(vararg parts: JComponent): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { parts.forEach { add(it) } }

    override fun getPreferredFocusedComponent(): JComponent = usernameField

    override fun doValidate(): ValidationInfo? = when {
        username.isBlank() -> ValidationInfo("Enter the Design username", usernameField)
        tokenName.isBlank() -> ValidationInfo("Enter a name for the token", nameField)
        daysField.text.isNotBlank() && (daysField.text.trim().toIntOrNull() ?: 0) <= 0 ->
            ValidationInfo("Validity must be a positive number of days", daysField)
        else -> null
    }

    private companion object {
        /** Names the token after this machine, so it is recognizable in Design's token list. */
        fun defaultTokenName(): String {
            val host = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeUnless { it.isBlank() }
            return if (host == null) "Flowable Atlas" else "Flowable Atlas ($host)"
        }
    }
}
