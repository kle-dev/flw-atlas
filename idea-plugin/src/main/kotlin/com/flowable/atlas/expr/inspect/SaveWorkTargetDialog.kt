package com.flowable.atlas.expr.inspect

import com.flowable.atlas.environment.AtlasEnvironmentSnapshot
import com.flowable.atlas.environment.AtlasEnvironments
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.environment.EnvironmentNames
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Gives a name to a Work URL the playground has been evaluating against for this session, so it stops
 * being a one-off and becomes an environment like any other.
 *
 * The counterpart of [PasteWorkUrlDialog], which deliberately creates nothing: pasting a link is a
 * place to go *once*, and most of them are. The moment a target turns out to be one you keep coming
 * back to, the only thing missing is the name — everything else (the URL, and whatever credentials
 * were typed for it) is already known. So that is the only thing this asks for.
 *
 * A name that matches an existing environment **joins** it rather than making a second one with the
 * same label: an environment that already has a Design server and no app is the normal way this
 * happens, and two environments called `QA` would make every picker in the plugin ambiguous. That case
 * leaves *Protected* alone — whether QA is guarded is a fact about QA, not something a save dialog
 * revisits.
 */
class SaveWorkTargetDialog(project: Project, private val baseUrl: String) : DialogWrapper(project) {

    private val catalog = AtlasEnvironments.getInstance()
    private val nameField = JBTextField(
        EnvironmentNames.suggest(baseUrl, catalog.environments().mapTo(HashSet()) { it.name }),
        24,
    )
    private val protectedBox = JCheckBox("Ask before evaluating against it")
    private val effect = JBLabel()

    /** The environment this should land in, trimmed — see [existing] for the "joins one" case. */
    val environmentName: String get() = nameField.text.trim()

    /** Only meaningful when a new environment is created; an existing one keeps its own setting. */
    val isProtected: Boolean get() = protectedBox.isSelected

    /** The environment [environmentName] names, when it is one that already exists. */
    fun existing(): AtlasEnvironmentSnapshot? = existingFor(environmentName)

    init {
        title = "Save as Environment"
        setOKButtonText("Save")
        init()
        nameField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateEffect()
            override fun removeUpdate(e: DocumentEvent) = updateEffect()
            override fun changedUpdate(e: DocumentEvent) = updateEffect()
        })
        updateEffect()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") {
            cell(nameField).align(AlignX.FILL).resizableColumn()
                .comment("What every picker will call it — the stage, not the host: DEV1, QA, PROD.")
        }
        row("App URL:") { label(baseUrl) }
        row("") { cell(protectedBox) }
        row("") { cell(effect) }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? {
        val name = environmentName
        if (name.isBlank()) return ValidationInfo("Give the environment a name", nameField)
        val hit = existingFor(name) ?: return null
        // Caught here rather than after the dialog closes: the slot being taken is the one thing the
        // user can still do something about, and the fix is to type a different name.
        if (catalog.connection(hit.id, ConnectionKind.WORK) != null) {
            return ValidationInfo("“${hit.name}” already has an app connection", nameField)
        }
        return null
    }

    private fun existingFor(name: String): AtlasEnvironmentSnapshot? =
        catalog.environments().firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Says which of the two things Save will do, while the name is still being typed. */
    private fun updateEffect() {
        val hit = existing()
        protectedBox.isEnabled = hit == null
        effect.text = when {
            environmentName.isBlank() -> ""
            hit == null -> "Creates a new environment."
            else -> "Adds this app to the existing “${hit.name}”."
        }
    }
}
