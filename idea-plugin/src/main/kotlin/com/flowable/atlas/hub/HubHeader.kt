package com.flowable.atlas.hub

import com.flowable.atlas.AtlasBuildInfo
import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.action.FlowableActionIds
import com.flowable.atlas.findings.AtlasFindingsService
import com.flowable.atlas.hub.sections.HubHost
import com.flowable.atlas.project.AtlasProjectRootService
import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
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
 *
 * Three rows where there used to be one: the picker on its own, as wide as the panel; the index's count,
 * age and Rebuild under it; the attention line wrapping, with its action on a row of its own. Side by
 * side they asked for 570 px — the reason the Hub had to be dragged half across the screen. The Hub's
 * own glyph left the row too: the tool window's title already carries it.
 */
internal class HubHeader(private val host: HubHost, private val onAttention: (HubAttention) -> Unit) {

    private var populating = false

    val projectCombo = HubLayout.narrow(ComboBox<String?>()).apply {
        renderer = textListCellRenderer { if (it.isNullOrBlank()) message("hub.project.whole") else it }
        addActionListener { if (!populating) chooseSubProject(selectedItem as? String) }
    }
    // The count is the way into the index — Search Everywhere's Flowable Model tab — and Rebuild sits
    // beside it instead of two levels down in ⋮; the age stays plain text.
    private val status = ActionLink("") { host.invokeAction(FlowableActionIds.GO_TO_MODEL) }.apply {
        toolTipText = FlowableActionIds.text(FlowableActionIds.GO_TO_MODEL)
        // Disabled while there is no index to search — and an ActionLink hides itself when disabled, which
        // is how "scanning…" and "index failed" never showed: the row said nothing at the one time it had
        // something to say.
        autoHideOnDisable = false
    }
    private val age = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val rebuild = ActionLink("") { host.invokeAction(FlowableActionIds.REBUILD_MODEL_INDEX) }.apply {
        icon = AllIcons.Actions.ForceRefresh
        toolTipText = FlowableActionIds.text(FlowableActionIds.REBUILD_MODEL_INDEX)
    }
    // Health: the last analysis's counts, a way into Atlas Findings. Its own row, always there, so the
    // header keeps its height whether or not anything has been analyzed.
    private val healthIcon = JBLabel()
    // Stretches and gives way first: four-digit counts beside *Analyze Again* are wider than the stripe,
    // and the counts end in "…" before the action does — the tooltip has them whole.
    private val health = HubLayout.narrow(ActionLink("") { host.invokeAction(FlowableActionIds.OPEN_ATLAS_FINDINGS) }).apply {
        autoHideOnDisable = false
        horizontalAlignment = javax.swing.SwingConstants.LEFT
    }
    private val analyzeAgain = ActionLink(message("findings.analyzeAgain")) {
        AtlasFindingsService.getInstance(host.project).refresh()
    }
    private val attentionIcon = JBLabel()
    private val attentionText = HubText(comment = false)
    private var current: HubAttention? = null
    private val attentionLink = ActionLink("") { current?.let(onAttention) }
    private var attentionRow: Row? = null
    private var attentionLinkRow: Row? = null

    fun build(panel: Panel) {
        panel.row {
            cell(projectCombo).align(AlignX.FILL).resizableColumn()
        }
        panel.row {
            cell(status).gap(RightGap.SMALL)
            cell(age).resizableColumn()
            cell(rebuild).align(AlignX.RIGHT)
        }
        panel.row {
            cell(healthIcon).gap(RightGap.SMALL)
            cell(health).align(AlignX.FILL).resizableColumn()
            cell(analyzeAgain).align(AlignX.RIGHT)
        }
        // The only rows in the panel whose presence changes: everything below keeps its place.
        attentionRow = panel.row {
            cell(attentionIcon).align(AlignY.TOP).gap(RightGap.SMALL)
            attentionText.place(this).align(AlignX.FILL).resizableColumn()
        }.visible(false)
        attentionLinkRow = panel.row { cell(attentionLink) }.visible(false)
    }

    fun apply(s: HubSnapshot, attention: HubAttention?) {
        applyProject(s)
        applyStatus(s)
        applyHealth(s.findings)
        current = attention
        attentionRow?.visible(attention != null)
        attentionLinkRow?.visible(attention != null)
        if (attention == null) return
        attentionIcon.icon = if (attention is HubAttention.ChooseProject) AllIcons.General.Information else AllIcons.General.Warning
        attentionText.text = when (attention) {
            is HubAttention.RemovedEnvironment -> message("hub.attention.removed", attention.kind.display)
            is HubAttention.ChooseProject -> message("hub.attention.chooseProject", attention.count)
            is HubAttention.IndexFailed -> message("hub.attention.indexFailed", attention.reason)
            is HubAttention.UnreadableArchives -> message("hub.attention.archives", attention.names.size)
            is HubAttention.StaleExplorer ->
                if (attention.changed.isEmpty()) message("hub.attention.stale")
                else message("hub.attention.staleChanged", attention.changed.size, attention.changed.take(3).joinToString(", "), (attention.changed.size - 3).coerceAtLeast(0))
        }
        attentionLink.text = when (attention) {
            is HubAttention.RemovedEnvironment -> FlowableActionIds.text(FlowableActionIds.MANAGE_ENVIRONMENTS)
            is HubAttention.ChooseProject -> message("hub.attention.chooseProject.action")
            is HubAttention.IndexFailed -> FlowableActionIds.text(FlowableActionIds.REBUILD_MODEL_INDEX)
            is HubAttention.UnreadableArchives -> message("hub.attention.archives.action")
            is HubAttention.StaleExplorer -> FlowableActionIds.text(FlowableActionIds.REGENERATE_ATLAS_EXPLORER)
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

    /** `142 models · 2 min ago` — the count a link into the index, the per-type breakdown, the scope and
     *  the version in its tooltip, Rebuild beside it. */
    private fun applyStatus(s: HubSnapshot) {
        val count = s.modelCount
        if (count == null) {
            status.text = message(if (s.indexFailure != null) "hub.status.failed" else "hub.status.scanning")
            status.isEnabled = false
            status.toolTipText = s.indexFailure
            age.text = ""
            return
        }
        status.isEnabled = true
        age.text = s.builtAtMillis.takeIf { it > 0 }?.let { "· " + HubAge.relative(it) } ?: ""
        status.text = message("hub.status.models", count)
        // The version is not repeated here: the panel's footer names it, in plain sight.
        status.toolTipText = HtmlBuilder().apply {
            val lines = listOfNotNull(
                s.builtAtMillis.takeIf { it > 0 }?.let {
                    message("hub.status.tooltip.scanned", DateFormatUtil.formatPrettyDateTime(it)) +
                        (s.scopeLabel?.let { scope -> " " + message("hub.status.tooltip.scope", scope) } ?: "")
                },
                s.typeCounts.takeIf { it.isNotEmpty() }?.joinToString(" · ") { (type, n) -> "$n ${type.display}" },
            )
            lines.forEachIndexed { i, line -> if (i > 0) br(); append(line) }
        }.wrapWithHtmlBody().toString()
    }

    /**
     * `3 defects · 41 advice` — a link into Atlas Findings, with *Analyze Again* beside it once a model
     * changed since. Before any analysis the link says *Analyze findings*: opening the window runs one.
     * The Hub never starts the analysis on its own; it is a full extract.
     */
    private fun applyHealth(f: FindingsHealth) {
        health.isEnabled = !f.running
        analyzeAgain.isVisible = !f.running && f.defects != null && f.stale
        when {
            f.running -> {
                healthIcon.icon = AnimatedIcon.Default.INSTANCE
                health.text = message("hub.health.running")
            }
            f.defects == null -> {
                healthIcon.icon = AllIcons.General.Information
                health.text = message("hub.health.none")
            }
            else -> {
                healthIcon.icon = if (f.defects > 0) AllIcons.General.Error else AllIcons.General.InspectionsOK
                health.text = message("findings.status.counts", f.defects, f.advice ?: 0)
            }
        }
        health.toolTipText = message("hub.health.tooltip", health.text, FlowableActionIds.text(FlowableActionIds.OPEN_ATLAS_FINDINGS))
    }

    /** For tests: the health row as it reads. */
    val healthText: String get() = health.text + if (analyzeAgain.isVisible) " · " + analyzeAgain.text else ""

    /** For tests: the count link's text. */
    val statusText: String get() = status.text

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
    val attention: String? get() = if (current != null) attentionText.text else null
}
