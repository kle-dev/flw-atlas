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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.CollectionListModel
import com.intellij.ui.PopupHandler
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.flowable.atlas.explorer.JcefSupport
import com.flowable.atlas.hub.HubLayout
import com.flowable.atlas.hub.HubText
import com.flowable.atlas.hub.NameMetaRow
import com.intellij.util.text.DateFormatUtil
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton

/**
 * The generated explorer pages and the two things one does with them: generate, open.
 *
 * Exactly one row of state — the list, or one grey line saying where it looked — and one row of
 * buttons, so the section is the same height with zero pages and with one. *Open in Browser* lives in the
 * list's context menu: it is the rare gesture. The buttons say *Generate…* and *Open*: the section is
 * called *Explorer*, and the full names made the pair 340 px wide in a stripe that has 280.
 */
internal class ExplorerSection(private val host: HubHost) : HubSection {

    override val id = "explorer"
    override val title: String get() = message("hub.section.explorer")

    private val model = CollectionListModel<ExplorerArtifact>()
    private val list = HubLayout.list(model).apply {
        visibleRowCount = 1
        cellRenderer = NameMetaRow<ExplorerArtifact> { a ->
            // Name on the left, age on the right, one fact per column — folder and full timestamp in the
            // tooltip, which is where a detail nobody scans for belongs.
            val where = a.relative.ifEmpty { "." }
            NameMetaRow.Parts(
                AtlasIcons.Explorer,
                a.path.fileName.toString(),
                if (a.modified > 0) HubAge.relative(a.modified) else "",
                if (a.modified > 0) message("hub.explorer.row.tooltip", where, DateFormatUtil.formatPrettyDateTime(a.modified)) else where,
            )
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedValue?.let(::open)
            }
        })
        PopupHandler.installPopupMenu(this, DefaultActionGroup(openInBrowserAction()), "AtlasHubExplorer")
    }
    private val scroll = HubLayout.listScroll(list)
    private val hint = HubText()
    private var listRow: Row? = null
    private var hintRow: Row? = null
    private lateinit var openButton: JButton
    private var browserAvailable = false

    override fun build(panel: Panel) {
        listRow = panel.row { cell(scroll).align(AlignX.FILL) }.visible(false)
        hintRow = panel.row { hint.place(this).align(AlignX.FILL) }
        panel.row {
            button(FlowableActionIds.text(FlowableActionIds.GENERATE_ATLAS_EXPLORER, FlowableActionIds.HUB_SECTION)) {
                host.invokeAction(FlowableActionIds.GENERATE_ATLAS_EXPLORER)
            }.applyToComponent { toolTipText = FlowableActionIds.text(FlowableActionIds.GENERATE_ATLAS_EXPLORER) }
            // Selected entry, or the newest one when nothing is selected (the list is sorted
            // most-recently-modified first). Disabled while there is nothing to open — it used to
            // fall through to the generate dialog, which is not what a button called Open does.
            openButton = button(FlowableActionIds.text(FlowableActionIds.OPEN_ATLAS_EXPLORER, FlowableActionIds.HUB_SECTION)) {
                (list.selectedValue ?: model.items.firstOrNull())?.let(::open)
            }.applyToComponent { toolTipText = FlowableActionIds.text(FlowableActionIds.OPEN_ATLAS_EXPLORER) }.component
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
        // mismatch.
        hint.text = message("hub.explorer.empty", s.searchedIn)
        hint.component.toolTipText = message("hub.explorer.empty.tooltip")
    }

    /** Resolves the file off the EDT — a refresh of a folder the IDE never saw can take a moment. */
    private fun open(artifact: ExplorerArtifact) {
        val project = host.project
        ApplicationManager.getApplication().executeOnPooledThread {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(artifact.path)
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                when {
                    vf != null && JcefSupport.isAvailable() -> AtlasExplorerOpener.openInIde(project, vf)
                    AtlasBrowser.canOpenFiles() -> AtlasBrowser.open(artifact.path)   // JCEF unavailable → external browser
                    else -> AtlasNotifications.group()
                        .createNotification(
                            message("hub.explorer.cannotOpen.title"),
                            message("hub.explorer.cannotOpen", artifact.path.fileName.toString()),
                            NotificationType.WARNING,
                        )
                        .notify(project)
                }
            }, ModalityState.any())
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
}
