package com.flowable.atlas.settings.connections

import com.flowable.atlas.environment.ConnectionKind
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JCheckBox
import javax.swing.JComponent

/**
 * One environment's own two facts — its name and whether it is protected — plus a summary of the
 * connections inside it.
 *
 * The summary is not decoration: it is where a half-configured environment gets finished. An
 * environment with a running app and no Design server is a perfectly ordinary thing (a QA stage nobody
 * models against), so it is shown **without any warning marker** — but the *Add* link is right there
 * for when it simply was not done yet, instead of only on a toolbar button the user has to find.
 */
class EnvironmentForm(
    private val onEditConnection: (ConnectionKind) -> Unit,
    private val onAddConnection: (ConnectionKind) -> Unit,
) {

    private val nameField = JBTextField(28)
    private val protectedBox = JCheckBox("Ask before pulling from or evaluating against this environment")
    private val designSummary = JBLabel()
    private val workSummary = JBLabel()

    private lateinit var designEdit: JComponent
    private lateinit var designAdd: JComponent
    private lateinit var workEdit: JComponent
    private lateinit var workAdd: JComponent

    private var current: ConnectionsDraft.Env? = null

    val component: JComponent = panel {
        row("Name:") { cell(nameField).align(AlignX.FILL) }
        row("") { cell(protectedBox) }
        row("") {
            comment(
                "Atlas asks before pulling from a protected environment or evaluating an expression against " +
                    "it, and marks it with a lock wherever it can be picked.",
            )
        }
        separator()
        row("Flowable Design:") {
            cell(designSummary)
            link("Edit") { onEditConnection(ConnectionKind.DESIGN) }.applyToComponent { designEdit = this }
            link("Add") { onAddConnection(ConnectionKind.DESIGN) }.applyToComponent { designAdd = this }
        }
        row("Flowable Work:") {
            cell(workSummary)
            link("Edit") { onEditConnection(ConnectionKind.WORK) }.applyToComponent { workEdit = this }
            link("Add") { onAddConnection(ConnectionKind.WORK) }.applyToComponent { workAdd = this }
        }
    }

    fun load(env: ConnectionsDraft.Env, draft: ConnectionsDraft) {
        current = env
        nameField.text = env.name
        protectedBox.isSelected = env.requireConfirmation
        val connections = draft.connectionsOf(env.id)
        apply(ConnectionKind.DESIGN, connections.firstOrNull { it.kind == ConnectionKind.DESIGN }, designSummary, designEdit, designAdd)
        apply(ConnectionKind.WORK, connections.firstOrNull { it.kind == ConnectionKind.WORK }, workSummary, workEdit, workAdd)
    }

    fun flush() {
        val env = current ?: return
        env.name = nameField.text.trim()
        env.requireConfirmation = protectedBox.isSelected
    }

    /** Focus and select the name, so the next keystroke renames a freshly added environment. */
    fun focusName() {
        nameField.requestFocusInWindow()
        nameField.selectAll()
    }

    private fun apply(
        kind: ConnectionKind,
        connection: ConnectionsDraft.Conn?,
        summary: JBLabel,
        edit: JComponent,
        add: JComponent,
    ) {
        summary.text = connection?.baseUrl?.ifBlank { "(no URL yet)" } ?: "not configured"
        summary.foreground = if (connection == null) JBColor.GRAY else JBColor.foreground()
        summary.toolTipText = if (connection == null) "This environment has no ${kind.display} server" else null
        edit.isVisible = connection != null
        add.isVisible = connection == null
    }
}
