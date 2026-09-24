package com.flowable.atlas.hub

import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.environment.AtlasConnection
import com.flowable.atlas.environment.AtlasConnectionSelection
import com.flowable.atlas.environment.AtlasConnectionSelection.Resolution
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.environment.ConnectionLabels
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import javax.swing.DefaultComboBoxModel

/**
 * The environment row the Design Pull and Playground sections share: a combo whose rows carry a padlock
 * when the environment is protected, and which says *environment was removed* in itself when the stored
 * pointer no longer resolves. That used to be a red note beside the combo, repeating the attention line
 * above it and making the row too wide for a side stripe; the attention line carries the fix.
 *
 * A real combo rather than a label with a "Change…" link, because the switch is the gesture the panel is
 * organised around and a closed combo says "this is yours to change" without anyone having to discover it.
 * A pointer that no longer resolves gets its own wording rather than quietly reading "not set": the two
 * need different fixes, and silently swapping in another environment is the one thing this must never do.
 */
internal class EnvironmentPicker(private val project: Project, private val kind: ConnectionKind) {

    private var populating = false
    private var placeholder = message("hub.env.notSet")

    val combo = HubLayout.narrow(ComboBox<AtlasConnection?>()).apply {
        renderer = listCellRenderer<AtlasConnection?> {
            // A padlock, not a prompt: PROD is shown as protected so nobody picks it by mistake.
            if (value?.requiresConfirmation == true) icon(AllIcons.Nodes.Padlock)
            text(value?.let { ConnectionLabels.pickerItem(it) } ?: placeholder)
        }
        addActionListener { if (!populating) choose(selectedItem as? AtlasConnection) }
    }
    fun placeIn(row: Row) {
        row.cell(combo).align(AlignX.FILL).resizableColumn()
    }

    val selected: AtlasConnection?
        get() = combo.selectedItem as? AtlasConnection

    /**
     * Fills the combo. "Not set" is a real, choosable entry; with nothing defined at all the combo is
     * disabled and says so, which keeps the row where it is instead of swapping it for a different one.
     */
    fun fill(available: List<AtlasConnection>, resolution: Resolution, hasAnyEnvironment: Boolean) {
        val current = (resolution as? Resolution.Selected)?.connection
        placeholder = when {
            resolution is Resolution.Dangling -> message("hub.env.removed")
            hasAnyEnvironment -> message("hub.env.notSet")
            else -> message("hub.env.none")
        }
        populating = true
        try {
            combo.model = DefaultComboBoxModel<AtlasConnection?>().apply {
                addElement(null)
                available.forEach { addElement(it) }
            }
            combo.selectedItem = current?.let { c -> available.firstOrNull { it.id == c.id } }
        } finally {
            populating = false
        }
        combo.isEnabled = hasAnyEnvironment
        combo.toolTipText = ConnectionLabels.tooltip(kind, resolution)
    }

    /** "Not set" is a choice the user made, not the absence of one — so it is *stored* as such. Merely
     *  unsetting the pointer let the single-environment fallback answer again. */
    fun choose(connection: AtlasConnection?) {
        if (connection == null) AtlasConnectionSelection.selectNone(project, kind)
        else AtlasConnectionSelection.select(project, kind, connection.id)
    }

    /** For tests: the row as a reader sees it — the name, or the placeholder. */
    fun line(): String = selected?.let { ConnectionLabels.pickerItem(it) } ?: placeholder
}
