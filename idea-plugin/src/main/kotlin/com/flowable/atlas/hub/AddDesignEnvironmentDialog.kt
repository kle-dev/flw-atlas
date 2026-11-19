package com.flowable.atlas.hub

import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.environment.AtlasEnvironmentSnapshot
import com.flowable.atlas.environment.AtlasEnvironments
import com.flowable.atlas.environment.BaseUrls
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.environment.EnvironmentNames
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The Hub's **+** beside the Design environment picker: a Design server, a name for it, and the login —
 * the three things a pull needs, asked for where the pull is.
 *
 * *Settings → Environments* stays the place for everything else an environment can have (a Work app,
 * Control and Hub links, an access token, *Protected*, sharing with the project). This is the short
 * way to the common case, and it writes into the same catalog, so what it creates is an ordinary
 * environment that page can edit afterwards.
 *
 * A name that matches an existing environment **joins** it, exactly as [com.flowable.atlas.expr.inspect.SaveWorkTargetDialog]
 * does for a Work app: an environment that has a running app and no Design server yet is how this
 * happens, and two environments with one name would make every picker ambiguous.
 */
internal class AddDesignEnvironmentDialog(project: Project) : DialogWrapper(project) {

    private val catalog = AtlasEnvironments.getInstance()
    private val urlField = JBTextField(30)
    private val nameField = JBTextField(30)
    private val userField = JBTextField(30)
    private val passwordField = JBPasswordField()
    private val effect = JBLabel()

    /** False once the name was typed by hand — until then it follows the URL. */
    private var nameFollowsUrl = true
    private var settingName = false

    val environmentName: String get() = nameField.text.trim()
    val baseUrl: String get() = BaseUrls.normalize(ConnectionKind.DESIGN, urlField.text)
    val username: String get() = userField.text.trim()
    val password: String get() = String(passwordField.password)

    /** The environment [environmentName] names, when it is one that already exists. */
    fun existing(): AtlasEnvironmentSnapshot? =
        catalog.environments().firstOrNull { it.name.equals(environmentName, ignoreCase = true) }

    init {
        title = message("dialog.addDesignEnvironment.title")
        setOKButtonText("Add")
        init()
        urlField.document.addDocumentListener(listener {
            if (nameFollowsUrl) {
                settingName = true
                try {
                    nameField.text = if (urlField.text.isBlank()) "" else EnvironmentNames.suggest(
                        baseUrl, catalog.environments().mapTo(HashSet()) { it.name },
                    )
                } finally {
                    settingName = false
                }
            }
            updateEffect()
        })
        nameField.document.addDocumentListener(listener {
            if (!settingName) nameFollowsUrl = false
            updateEffect()
        })
        updateEffect()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Design URL:") {
            cell(urlField).align(AlignX.FILL).resizableColumn()
        }
        row("Name:") {
            cell(nameField).align(AlignX.FILL)
                .comment("What every picker will call it — the stage, not the host: DEV1, QA, PROD.")
        }
        row("Username:") { cell(userField).align(AlignX.FILL) }
        row("Password:") {
            cell(passwordField).align(AlignX.FILL)
                .comment("Stored in the IDE password safe. An access token, a Work app or <i>Protected</i>: Settings → Environments.")
        }
        row("") { cell(effect) }
    }

    override fun getPreferredFocusedComponent(): JComponent = urlField

    override fun doValidate(): ValidationInfo? {
        if (urlField.text.isBlank()) return ValidationInfo("Enter the Flowable Design URL", urlField)
        if (BaseUrls.host(baseUrl).isBlank()) return ValidationInfo("This is not a URL Atlas can reach", urlField)
        if (environmentName.isBlank()) return ValidationInfo("Give the environment a name", nameField)
        val hit = existing()
        if (hit != null && catalog.connection(hit.id, ConnectionKind.DESIGN) != null) {
            return ValidationInfo("“${hit.name}” already has a Design connection", nameField)
        }
        if (username.isBlank()) return ValidationInfo("Enter the username", userField)
        return null
    }

    /** Says which of the two things Add will do, while the name is still being typed. */
    private fun updateEffect() {
        val hit = existing()
        effect.text = when {
            environmentName.isBlank() -> ""
            hit == null -> "Creates a new environment."
            else -> "Adds this Design server to the existing “${hit.name}”."
        }
    }

    private fun listener(onChange: () -> Unit) = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = onChange()
        override fun removeUpdate(e: DocumentEvent) = onChange()
        override fun changedUpdate(e: DocumentEvent) = onChange()
    }
}
