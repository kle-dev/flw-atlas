package com.flowable.atlas.hub.sections

import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.action.CopyModelKeyAction
import com.flowable.atlas.action.FlowableActionIds
import com.flowable.atlas.hub.HubLayout
import com.flowable.atlas.hub.HubLists
import com.flowable.atlas.hub.HubText
import com.flowable.atlas.hub.NameMetaRow
import com.flowable.atlas.hub.HubSnapshot
import com.flowable.atlas.hub.RecentModel
import com.flowable.atlas.hub.RecentModelsService
import com.flowable.atlas.icons.AtlasIcons
import com.flowable.atlas.intention.OpenInAtlasExplorerIntention
import com.flowable.atlas.navigation.ModelKeyTargets
import com.flowable.atlas.navigation.se.ArchivePaths
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware
import com.intellij.ui.CollectionListModel
import com.intellij.ui.PopupHandler
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/**
 * The models opened most recently, newest first — the way back to the process you were reading before
 * the Ctrl+click took you three files away. Double-click opens the model at its key; the context menu
 * copies the key, opens the model's explorer page, or takes one model or all of them off the list. One grey line until something has been opened.
 */
internal class RecentModelsSection(private val host: HubHost) : HubSection {

    override val id = "recent"
    override val title: String get() = message("hub.section.recent")

    private val model = CollectionListModel<RecentModel>()
    private val list = HubLayout.list(model).apply {
        visibleRowCount = 1
        // Type icon · key on the left, the file on the right — the Search Everywhere row's shape. The
        // file gives way first when the stripe is narrow; the tooltip has type, name and path whole.
        cellRenderer = NameMetaRow<RecentModel> { r ->
            NameMetaRow.Parts(
                r.type?.let(AtlasIcons::forType) ?: AtlasIcons.Model,
                r.key,
                r.file.name,
                listOf(r.type?.display, r.name.takeIf { it.isNotBlank() && it != r.key }, ArchivePaths.displayPath(r.file))
                    .filterNotNull().joinToString(" · "),
            )
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedValue?.let(::open)
            }
        })
        PopupHandler.installPopupMenu(this, DefaultActionGroup(
            copyKeyAction(), openInExplorerAction(), Separator.getInstance(), removeAction(), clearAction(),
        ), "AtlasHubRecent")
    }
    private val scroll = HubLayout.listScroll(list)
    private val hint = HubText().apply { text = message("hub.recent.empty") }
    private var listRow: Row? = null
    private var hintRow: Row? = null

    override fun build(panel: Panel) {
        listRow = panel.row { cell(scroll).align(AlignX.FILL) }.visible(false)
        hintRow = panel.row { hint.place(this).align(AlignX.FILL) }
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

    private fun removeAction(): AnAction = object : AnAction(message("hub.recent.remove")), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            list.selectedValue?.let { RecentModelsService.getInstance(host.project).remove(it.file) }
        }
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = list.selectedValue != null }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private fun clearAction(): AnAction = object : AnAction(message("hub.recent.clear")), DumbAware {
        override fun actionPerformed(e: AnActionEvent) = RecentModelsService.getInstance(host.project).clear()
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = model.size > 0 }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    /** For tests: the keys in the order shown. */
    val keys: List<String> get() = model.items.map { it.key }
}
