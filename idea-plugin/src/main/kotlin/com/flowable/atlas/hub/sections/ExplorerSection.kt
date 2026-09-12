package com.flowable.atlas.hub.sections

import com.flowable.atlas.AtlasNotifications
import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.action.FlowableActionIds
import com.flowable.atlas.explorer.AtlasBrowser
import com.flowable.atlas.explorer.AtlasExplorerOpener
import com.flowable.atlas.hub.ExplorerArtifact
import com.flowable.atlas.hub.HubAge
import com.flowable.atlas.hub.HubLists
import com.flowable.atlas.hub.HubSnapshot
import com.flowable.atlas.icons.AtlasIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CollectionListModel
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.flowable.atlas.explorer.JcefSupport
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * The generated explorer pages and the two things one does with them: generate, open.
 *
 * Exactly one row of state — the list, or one grey line saying where it looked — and one row of
 * buttons, so the section is the same height with zero pages and with one. *Open in Browser* moved into
 * the list's context menu: it is the rare gesture, and a third button made the row wrap in a narrow stripe.
 */
internal class ExplorerSection(private val host: HubHost) : HubSection {

    private val model = CollectionListModel<ExplorerArtifact>()
    private val list = JBList(model).apply {
        visibleRowCount = 1
        cellRenderer = ArtifactRow()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedValue?.let(::open)
            }
        })
        PopupHandler.installPopupMenu(this, DefaultActionGroup(openInBrowserAction()), "AtlasHubExplorer")
    }
    private val scroll = JBScrollPane(list)
    private val hint = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private var listRow: Row? = null
    private var hintRow: Row? = null
    private lateinit var openButton: JButton
    private var browserAvailable = false

    override fun build(panel: Panel) {
        panel.group(message("hub.section.explorer")) {
            listRow = row { cell(scroll).align(AlignX.FILL) }.visible(false)
            hintRow = row { cell(hint) }
            row {
                button(FlowableActionIds.text(FlowableActionIds.GENERATE_ATLAS_EXPLORER)) {
                    host.invokeAction(FlowableActionIds.GENERATE_ATLAS_EXPLORER)
                }
                // Selected entry, or the newest one when nothing is selected (the list is sorted
                // most-recently-modified first). Disabled while there is nothing to open — it used to
                // fall through to the generate dialog, which is not what a button called Open does.
                openButton = button(FlowableActionIds.text(FlowableActionIds.OPEN_ATLAS_EXPLORER)) {
                    (list.selectedValue ?: model.items.firstOrNull())?.let(::open)
                }.component
            }
        }
    }

    override fun apply(s: HubSnapshot) {
        browserAvailable = s.browserAvailable
        val selected = list.selectedValue?.path
        model.replaceAll(s.artifacts)
        selected?.let { keep -> s.artifacts.firstOrNull { it.path == keep }?.let { list.setSelectedValue(it, false) } }
        HubLists.sizeToContent(list, s.artifacts.size)
        scroll.revalidate()
        listRow?.visible(s.artifacts.isNotEmpty())
        hintRow?.visible(s.artifacts.isEmpty())
        openButton.isEnabled = s.artifacts.isNotEmpty()
        // Not "nothing has been generated" — this panel cannot know that. *Generate…* writes wherever
        // you point it, and the search is scoped to the active Flowable project's output folder, so a
        // page saved elsewhere is invisible here. Naming the folder turns a wrong claim into a findable
        // mismatch. Plain text: the value is a user-typed folder name.
        hint.text = message("hub.explorer.empty", s.searchedIn)
        hint.toolTipText = message("hub.explorer.empty.tooltip")
    }

    private fun open(artifact: ExplorerArtifact) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(artifact.path)
        when {
            vf != null && JcefSupport.isAvailable() -> AtlasExplorerOpener.openInIde(host.project, vf)
            AtlasBrowser.canOpenFiles() -> AtlasBrowser.open(artifact.path)   // JCEF unavailable → external browser
            else -> AtlasNotifications.group()
                .createNotification(
                    message("hub.explorer.cannotOpen.title"),
                    message("hub.explorer.cannotOpen", artifact.path.fileName.toString()),
                    NotificationType.WARNING,
                )
                .notify(host.project)
        }
    }

    private fun openInBrowserAction(): AnAction =
        object : AnAction(message("hub.explorer.openInBrowser")), DumbAware {
            override fun actionPerformed(e: AnActionEvent) {
                list.selectedValue?.let { AtlasBrowser.open(it.path) }
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = browserAvailable && list.selectedValue != null
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

    /** For tests: the empty-state line, which has to name where it looked. */
    val hintText: String get() = hint.text

    /** For tests: how many rows the list reserves. */
    val rows: Int get() = list.visibleRowCount

    /**
     * Name on the left, age on the right, one fact per column — folder and full timestamp in the tooltip,
     * which is where a detail nobody scans for belongs. Two components in a BorderLayout, like the Search
     * Everywhere row, so the ages line up down the list.
     */
    private class ArtifactRow : JPanel(BorderLayout()), ListCellRenderer<ExplorerArtifact> {
        private val name = SimpleColoredComponent()
        private val age = SimpleColoredComponent().apply { ipad = JBUI.insetsRight(8) }

        init {
            add(name, BorderLayout.CENTER)
            add(age, BorderLayout.EAST)
            isOpaque = true
        }

        override fun getListCellRendererComponent(
            list: JList<out ExplorerArtifact>, value: ExplorerArtifact, index: Int, selected: Boolean, focus: Boolean,
        ): Component {
            name.clear()
            age.clear()
            name.icon = AtlasIcons.Explorer
            name.append(value.path.fileName.toString(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            if (value.modified > 0) age.append(HubAge.relative(value.modified), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            val bg = if (selected) list.selectionBackground else list.background
            val fg = if (selected) list.selectionForeground else list.foreground
            background = bg
            for (c in listOf(name, age)) { c.background = bg; c.foreground = fg }
            val where = value.relative.ifEmpty { "." }
            toolTipText = if (value.modified > 0) "$where, generated ${DateFormatUtil.formatPrettyDateTime(value.modified)}" else where
            return this
        }
    }
}
