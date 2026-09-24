package com.flowable.atlas.design

import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.COLUMNS_TINY
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.openapi.util.text.StringUtil
import com.flowable.atlas.FlowableAtlasBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import java.net.InetAddress
import javax.swing.JComponent

/**
 * Collects what [DesignClient.createAccessToken] needs so the user can mint a Design personal access
 * token without leaving the IDE — and, once stored, without a password in the keychain at all.
 *
 * **Input only:** the dialog performs no network or keychain access; [com.flowable.atlas.settings.connections.ServerConnectionForm]
 * runs the call on a pooled thread after OK and puts the token into its access-token field, which *Apply*
 * then stores. The username/password typed here authenticate that one request and are never persisted.
 *
 * Laid out with the UI DSL, one field per row with the labels in a column; it was a stack of flow rows
 * with the password beside the username, and the fields did not line up.
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
        title = FlowableAtlasBundle.message("dialog.createDesignToken.title")
        setOKButtonText("Create Token")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            text(
                "Signs in to <b>${StringUtil.escapeXmlEntities(baseUrl)}</b> once with your username and password to " +
                    "create a personal access token; from then on only the token is used. Design shows a " +
                    "token's value just once, so it goes straight into the access-token field, and Apply " +
                    "stores it in the IDE password safe. Behind SSO, Design switches the username and " +
                    "password off — create the token in Design itself with <i>Manage in Design…</i> instead.",
                maxLineLength = 70,
            )
        }
        row("Username:") { cell(usernameField).columns(COLUMNS_MEDIUM) }
        row("Password:") { cell(passwordField).columns(COLUMNS_MEDIUM) }
        row("Token name:") { cell(nameField).columns(COLUMNS_MEDIUM) }
        row("Valid for (days):") {
            cell(daysField).columns(COLUMNS_TINY).comment("Leave it empty for a token that does not expire.")
        }
    }

    /** For tests: the form, built the way the dialog builds it. */
    internal fun formForTest(): JComponent = createCenterPanel()

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
