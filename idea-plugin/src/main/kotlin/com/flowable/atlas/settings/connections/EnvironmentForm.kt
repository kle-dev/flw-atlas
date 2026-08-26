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
 * for when it simply was not done yet, instead of only on a toolbar button the user has to find. The
 * same goes for the link-only kinds: most environments will never have a Hub address, and one that
 * does not is not incomplete.
 */
class EnvironmentForm(
    private val onEditConnection: (ConnectionKind) -> Unit,
    private val onAddConnection: (ConnectionKind) -> Unit,
) {

    private val nameField = JBTextField(28)
    private val protectedBox = JCheckBox("Ask before pulling from or evaluating against this environment")

    /**
     * A row per [ConnectionKind], built from the enum rather than written out. The two that existed
     * were four fields and two hand-copied rows; a fifth product would have been four more of each, and
     * the first one anybody forgot would simply be missing from this page with nothing to notice.
     */
    private val rows = LinkedHashMap<ConnectionKind, Slot>()

    private class Slot(val summary: JBLabel = JBLabel()) {
        lateinit var edit: JComponent
        lateinit var add: JComponent
    }

    private var current: ConnectionsDraft.Env? = null

    val component: JComponent = panel {
        row("Name:") { cell(nameField).align(AlignX.FILL) }
        row("") { cell(protectedBox) }
        row("") {
            comment(
                "Atlas asks before pulling from a protected environment or evaluating an expression against " +
                    "it, and marks it with a lock wherever it can be picked. The Control and Hub addresses " +
                    "are links, so nothing asks before opening one.",
            )
        }
        separator()
        ConnectionKind.entries.forEach { kind ->
            val slot = Slot().also { rows[kind] = it }
            row("Flowable ${kind.display}:") {
                cell(slot.summary)
                link("Edit") { onEditConnection(kind) }.applyToComponent { slot.edit = this }
                link("Add") { onAddConnection(kind) }.applyToComponent { slot.add = this }
            }
        }
    }

    fun load(env: ConnectionsDraft.Env, draft: ConnectionsDraft) {
        current = env
        nameField.text = env.name
        protectedBox.isSelected = env.requireConfirmation
        // Name and protection come from the committed file for a shared environment. Editing them here
        // would be a local override of something the whole team reads — copy it instead, which the tree
        // offers and which produces a local environment that shadows this one.
        nameField.isEditable = !env.shared
        protectedBox.isEnabled = !env.shared
        val connections = draft.connectionsOf(env.id)
        rows.forEach { (kind, slot) ->
            apply(kind, connections.firstOrNull { it.kind == kind }, slot.summary, slot.edit, slot.add)
        }
    }

    fun flush() {
        val env = current ?: return
        if (env.shared) return
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
        summary.toolTipText = when {
            connection != null -> null
            kind.linkOnly -> "This environment has no ${kind.display} address to open"
            else -> "This environment has no ${kind.display} server"
        }
        edit.isVisible = connection != null
        add.isVisible = connection == null
    }
}
