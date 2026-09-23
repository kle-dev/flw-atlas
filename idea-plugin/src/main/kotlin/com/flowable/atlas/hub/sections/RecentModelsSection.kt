package com.flowable.atlas.hub.sections

import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.action.CopyModelKeyAction
import com.flowable.atlas.action.FlowableActionIds
import com.flowable.atlas.hub.HubLists
import com.flowable.atlas.hub.HubSnapshot
import com.flowable.atlas.hub.RecentModel
import com.flowable.atlas.icons.AtlasIcons
import com.flowable.atlas.intention.OpenInAtlasExplorerIntention
import com.flowable.atlas.navigation.ModelKeyTargets
import com.flowable.atlas.navigation.se.ArchivePaths
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
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
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * The models opened most recently, newest first — the way back to the process you were reading before
 * the Ctrl+click took you three files away. Double-click opens the model at its key; the context menu
 * copies the key or opens the model's explorer page. One grey line until something has been opened.
 */
internal class RecentModelsSection(private val host: HubHost) : HubSection {

    private val model = CollectionListModel<RecentModel>()
    private val list = JBList(model).apply {
        visibleRowCount = 1
        cellRenderer = RecentRow()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedValue?.let(::open)
            }
        })
        PopupHandler.installPopupMenu(this, DefaultActionGroup(copyKeyAction(), openInExplorerAction()), "AtlasHubRecent")
    }
    private val scroll = JBScrollPane(list)
    private val hint = JBLabel(message("hub.recent.empty")).apply { foreground = UIUtil.getContextHelpForeground() }
    private var listRow: Row? = null
    private var hintRow: Row? = null

    override fun build(panel: Panel) {
        panel.group(message("hub.section.recent")) {
            listRow = row { cell(scroll).align(AlignX.FILL) }.visible(false)
            hintRow = row { cell(hint) }
        }
    }

    override fun apply(s: HubSnapshot) {
        val selected = list.selectedValue?.file
        model.replaceAll(s.recentModels)
        selected?.let { keep -> s.recentModels.firstOrNull { it.file == keep }?.let { list.setSelectedValue(it, false) } }
        HubLists.sizeToContent(list, s.recentModels.size)
        scroll.revalidate()
        listRow?.visible(s.recentModels.isNotEmpty())
        hintRow?.visible(s.recentModels.isEmpty())
    }

    /** On the key's declaration when the index knows it, else the top of the file. */
    private fun open(recent: RecentModel) {
        if (!recent.file.isValid) return
        ModelKeyTargets.openAt(host.project, recent.file) { recent.entry?.let { ModelKeyTargets.lineColumn(it) } }
    }

    private fun copyKeyAction(): AnAction = object : AnAction(FlowableActionIds.text(FlowableActionIds.COPY_MODEL_KEY)), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            list.selectedValue?.let { CopyModelKeyAction.copy(host.project, it.key, null) }
        }
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = list.selectedValue != null }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private fun openInExplorerAction(): AnAction = object : AnAction(OpenInAtlasExplorerIntention.TEXT), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            list.selectedValue?.entry?.let { OpenInAtlasExplorerIntention.openPage(host.project, it) }
        }
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = list.selectedValue?.entry != null }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    /** For tests: the keys in the order shown. */
    val keys: List<String> get() = model.items.map { it.key }

    /** Type icon · key on the left, the file on the right — the Search Everywhere row's shape. */
    private class RecentRow : JPanel(BorderLayout()), ListCellRenderer<RecentModel> {
        private val key = SimpleColoredComponent()
        private val where = SimpleColoredComponent().apply { ipad = JBUI.insetsRight(8) }

        init {
            add(key, BorderLayout.CENTER)
            add(where, BorderLayout.EAST)
            isOpaque = true
        }

        override fun getListCellRendererComponent(
            list: JList<out RecentModel>, value: RecentModel, index: Int, selected: Boolean, focus: Boolean,
        ): Component {
            key.clear()
            where.clear()
            key.icon = value.type?.let(AtlasIcons::forType) ?: AtlasIcons.Model
            key.append(value.key, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            where.append(value.file.name, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            val bg = if (selected) list.selectionBackground else list.background
            val fg = if (selected) list.selectionForeground else list.foreground
            background = bg
            for (c in listOf(key, where)) { c.background = bg; c.foreground = fg }
            toolTipText = listOf(value.type?.display, value.name.takeIf { it.isNotBlank() && it != value.key }, ArchivePaths.displayPath(value.file))
                .filterNotNull().joinToString(" · ")
            return this
        }
    }
}
