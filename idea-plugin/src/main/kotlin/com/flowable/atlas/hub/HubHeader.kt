package com.flowable.atlas.hub

import com.flowable.atlas.AtlasBuildInfo
import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.action.FlowableActionIds
import com.flowable.atlas.hub.sections.HubHost
import com.flowable.atlas.icons.AtlasIcons
import com.flowable.atlas.project.AtlasProjectRootService
import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.UIUtil
import javax.swing.DefaultComboBoxModel

/**
 * The status header: which Flowable project Atlas is about, how much it knows and how fresh that is, and
 * — only when something needs a hand — one attention line with the one action that fixes it.
 *
 * The scope picker is a combo for the same reason the environment rows are: a line reading *Whole
 * project* with no control beside it answers "what is this?" but not "is this mine to change?", and in a
 * repository holding several apps that second question is the whole point of the row.
 */
internal class HubHeader(private val host: HubHost, private val onAttention: (HubAttention) -> Unit) {

    private var populating = false

    val projectCombo = ComboBox<String?>().apply {
        renderer = textListCellRenderer { if (it.isNullOrBlank()) message("hub.project.whole") else it }
        addActionListener { if (!populating) chooseSubProject(selectedItem as? String) }
    }
    private val status = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val attentionLabel = JBLabel()
    private var current: HubAttention? = null
    private val attentionLink = ActionLink("") { current?.let(onAttention) }
    private var attentionRow: Row? = null

    fun build(panel: Panel) {
        panel.row {
            icon(AtlasIcons.Hub).gap(RightGap.SMALL)
            cell(projectCombo).align(AlignX.FILL).resizableColumn()
            cell(status)
        }
        // The only row in the panel whose presence changes: everything below keeps its place.
        attentionRow = panel.row {
            cell(attentionLabel).resizableColumn()
            cell(attentionLink)
        }.visible(false)
    }

    fun apply(s: HubSnapshot, attention: HubAttention?) {
        applyProject(s)
        applyStatus(s)
        current = attention
        attentionRow?.visible(attention != null)
        if (attention == null) return
        attentionLabel.icon = if (attention is HubAttention.ChooseProject) AllIcons.General.Information else AllIcons.General.Warning
        attentionLabel.text = when (attention) {
            is HubAttention.RemovedEnvironment -> message("hub.attention.removed", attention.kind.display)
            is HubAttention.ChooseProject -> message("hub.attention.chooseProject", attention.count)
            is HubAttention.UnreadableArchives -> message("hub.attention.archives", attention.names.size)
            HubAttention.StaleExplorer -> message("hub.attention.stale")
        }
        attentionLink.text = when (attention) {
            is HubAttention.RemovedEnvironment -> FlowableActionIds.text(FlowableActionIds.MANAGE_ENVIRONMENTS)
            is HubAttention.ChooseProject -> message("hub.attention.chooseProject.action")
            is HubAttention.UnreadableArchives -> message("hub.attention.archives.action")
            HubAttention.StaleExplorer -> FlowableActionIds.text(FlowableActionIds.REGENERATE_ATLAS_EXPLORER)
        }
    }

    /**
     * Fills the project picker. The active sub-project is always in the list even when detection has not
     * run yet, so the row can never show *Whole project* while Atlas is scoped to something else.
     */
    private fun applyProject(s: HubSnapshot) {
        val items = (listOf("") + s.subProjects + s.activeSubProject).distinct()
        populating = true
        try {
            projectCombo.model = DefaultComboBoxModel<String?>().apply { items.forEach { addElement(it) } }
            projectCombo.selectedItem = s.activeSubProject
        } finally {
            populating = false
        }
        projectCombo.toolTipText = when {
            s.activeSubProject.isNotBlank() -> s.activeSubProject
            s.subProjects.isEmpty() -> message("hub.project.tooltip.whole")
            else -> message("hub.project.tooltip.pick", s.subProjects.size)
        }
    }

    /** `142 models · 2 min ago` — the per-type breakdown, the scope and the version live in the tooltip. */
    private fun applyStatus(s: HubSnapshot) {
        val count = s.modelCount
        if (count == null) {
            status.text = message("hub.status.scanning")
            status.toolTipText = null
            return
        }
        val age = s.builtAtMillis.takeIf { it > 0 }?.let { " · " + HubAge.relative(it) } ?: ""
        status.text = message("hub.status.models", count) + age
        status.toolTipText = buildString {
            append("<html>Flowable Atlas ").append(atlasVersion())
            s.builtAtMillis.takeIf { it > 0 }?.let {
                append("<br>").append(message("hub.status.tooltip.scanned", DateFormatUtil.formatPrettyDateTime(it)))
                s.scopeLabel?.let { scope -> append(' ').append(message("hub.status.tooltip.scope", scope)) }
            }
            if (s.typeCounts.isNotEmpty()) {
                append("<br>").append(s.typeCounts.joinToString(" · ") { (type, n) -> "$n ${type.display}" })
            }
            append("</html>")
        }
    }

    /**
     * Switches the active Flowable sub-project. Storing it also records that a choice was *made*, which
     * is what retires the "choose one" line — `""` alone cannot tell a deliberate whole-repository from a
     * default nobody looked at.
     */
    fun chooseSubProject(relPath: String?) {
        AtlasProjectRootService.getInstance(host.project).setActiveSubProject(relPath.orEmpty())
    }


    companion object {
        /** The running plugin's version; falls back to the baked :core build version, which is the same
         *  Gradle version, so any mismatch would itself signal drift. */
        fun atlasVersion(): String =
            PluginManagerCore.getPlugin(PluginId.getId("com.flowable.atlas"))?.version ?: AtlasBuildInfo.VERSION
    }

    // -- for tests -------------------------------------------------------------------------------

    val projectItems: List<String> get() = (0 until projectCombo.itemCount).map { projectCombo.getItemAt(it).orEmpty() }
    val attentionText: String? get() = if (attentionRow?.let { current } != null) attentionLabel.text else null
}
