package com.flowable.atlas.hub

import com.flowable.atlas.action.FlowableActionIds
import com.flowable.atlas.action.RebuildModelIndexAction
import com.flowable.atlas.design.DesignPullService
import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.events.AtlasEventsListener
import com.flowable.atlas.explorer.AtlasExplorerFiles
import com.flowable.atlas.explorer.AtlasExplorerOpener
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.settings.FlowableAtlasConfigurable
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.SingleAlarm
import com.intellij.util.text.DateFormatUtil
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JList

/**
 * Content of the Atlas Hub tool window: model-index status (with Rebuild), the generated
 * `*.explorer.html` pages (double-click opens the embedded viewer), and the Flowable Design sync
 * state — all refreshed via [AtlasEvents] with a debounce. Data is gathered on a pooled thread and
 * only applied on the EDT; the panel never triggers a blocking index build itself (it displays
 * [FlowableModelIndexService.cachedOrNull] only).
 */
class AtlasHubPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private data class ExplorerArtifact(val path: Path, val relative: String, val modified: Long)

    private data class Snapshot(
        val projectText: String,
        val showChangeLink: Boolean,
        val indexText: String,
        val artifacts: List<ExplorerArtifact>,
        val designText: String,
    )

    private val projectStatus = JBLabel()
    private var changeProjectLink: javax.swing.JComponent? = null
    private val indexStatus = JBLabel()
    private val designStatus = JBLabel()
    private val artifactsModel = CollectionListModel<ExplorerArtifact>()
    private val artifactsList = JBList(artifactsModel).apply {
        visibleRowCount = 8
        setEmptyText("No explorer generated yet")
        cellRenderer = object : ColoredListCellRenderer<ExplorerArtifact>() {
            override fun customizeCellRenderer(
                list: JList<out ExplorerArtifact>, value: ExplorerArtifact,
                index: Int, selected: Boolean, hasFocus: Boolean,
            ) {
                icon = AllIcons.Nodes.PpWeb
                append(value.path.fileName.toString(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${value.relative}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (value.modified > 0) {
                    append("  ·  ${DateFormatUtil.formatPrettyDateTime(value.modified)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedArtifact()?.let(::openArtifact)
            }
        })
    }

    private val refreshAlarm = SingleAlarm(::refreshNow, 300, this)

    init {
        toolbar = ActionManager.getInstance()
            .createActionToolbar("AtlasHub", buildToolbarGroup(), true)
            .also { it.targetComponent = this }
            .component

        setContent(JBScrollPane(buildContent()))

        project.messageBus.connect(this).subscribe(AtlasEvents.TOPIC, object : AtlasEventsListener {
            override fun modelIndexUpdated() = refreshAlarm.cancelAndRequest()
            override fun artifactsGenerated(explorerHtml: Path?, written: List<Path>) = refreshAlarm.cancelAndRequest()
            override fun designPullFinished(succeeded: Boolean) = refreshAlarm.cancelAndRequest()
            override fun activeSubProjectChanged() = refreshAlarm.cancelAndRequest()
        })
        refreshAlarm.request()
    }

    private fun buildToolbarGroup(): DefaultActionGroup {
        val am = ActionManager.getInstance()
        val group = DefaultActionGroup()
        listOf(
            FlowableActionIds.GENERATE_ATLAS_EXPLORER,
            FlowableActionIds.OPEN_ATLAS_EXPLORER,
            FlowableActionIds.PULL_FROM_DESIGN,
        ).forEach { id -> am.getAction(id)?.let(group::add) }
        group.addSeparator()
        group.add(object : AnAction("Refresh", "Refresh the Atlas Hub", AllIcons.Actions.Refresh), DumbAware {
            override fun actionPerformed(e: AnActionEvent) = refreshAlarm.cancelAndRequest()
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        })
        return group
    }

    private fun buildContent() = panel {
        group("Flowable Project") {
            row { cell(projectStatus) }
            row {
                link("Change…") { chooseSubProject() }
                    .applyToComponent { changeProjectLink = this }
            }
        }
        group("Model Index") {
            row { cell(indexStatus) }
            row {
                link("Rebuild") { RebuildModelIndexAction.rebuild(project) }
            }
        }
        group("Atlas Explorer") {
            row {
                cell(JBScrollPane(artifactsList)).align(AlignX.FILL)
            }
            row {
                link("Generate…") { invokeAction(FlowableActionIds.GENERATE_ATLAS_EXPLORER) }
                // Selected entry, or the newest one when nothing is selected (the list is sorted
                // most-recently-modified first) — the link must never be a silent no-op.
                link("Open in Browser") {
                    (selectedArtifact() ?: artifactsModel.items.firstOrNull())
                        ?.let { BrowserUtil.browse(it.path.toFile()) }
                        ?: invokeAction(FlowableActionIds.GENERATE_ATLAS_EXPLORER)
                }
            }
        }
        group("Flowable Design") {
            row { cell(designStatus) }
            row {
                link("Pull from Design") { invokeAction(FlowableActionIds.PULL_FROM_DESIGN) }
                link("Configure…") { invokeAction(FlowableActionIds.CONFIGURE_DESIGN_CONNECTION) }
            }
        }
        separator()
        row {
            link("Settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, FlowableAtlasConfigurable::class.java)
            }
            link("Expression Playground") {
                ToolWindowManager.getInstance(project).getToolWindow("Flowable Expressions")?.activate(null, true)
            }
        }
    }

    private fun invokeAction(id: String) {
        val action = ActionManager.getInstance().getAction(id) ?: return
        val context = DataManager.getInstance().getDataContext(this)
        val event = AnActionEvent.createEvent(action, context, null, "AtlasHub", ActionUiKind.NONE, null)
        ActionUtil.performAction(action, event)
    }

    /** Popup to switch the active Flowable sub-project (or back to the whole project). */
    private fun chooseSubProject() {
        val rootService = AtlasProjectRootService.getInstance(project)
        val detected = rootService.detectedOrNull().orEmpty()
        val labels = listOf(WHOLE_PROJECT_LABEL) + detected.map { it.relPath }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(labels)
            .setTitle("Select Flowable Project")
            .setItemChosenCallback { label ->
                rootService.setActiveSubProject(if (label == WHOLE_PROJECT_LABEL) "" else label)
            }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun selectedArtifact(): ExplorerArtifact? = artifactsList.selectedValue

    private fun openArtifact(artifact: ExplorerArtifact) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(artifact.path)
        when {
            vf != null && JBCefApp.isSupported() -> AtlasExplorerOpener.openInIde(project, vf)
            else -> BrowserUtil.browse(artifact.path.toFile())   // JCEF unavailable → external browser
        }
    }

    // ---- refresh --------------------------------------------------------------------------

    /** Fired by the (EDT) alarm; gathers on a pooled thread, applies on the EDT. */
    private fun refreshNow() {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val snapshot = gather()
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) apply(snapshot)
            }, ModalityState.any())
        }
    }

    private fun gather(): Snapshot {
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        val rootService = AtlasProjectRootService.getInstance(project)
        val base = rootService.activeProjectDir()

        val active = rootService.activeSubProject()
        val detected = rootService.detectedOrNull()
        if (detected == null) rootService.detectAsync { refreshAlarm.cancelAndRequest() }
        val subCount = detected?.size ?: 0
        val chosen = rootService.hasChosenProject()
        val projectText = when {
            active.isNotBlank() -> "<html>Project: <b>$active</b></html>"
            subCount >= 2 && !chosen -> "<html>⚠ $subCount Flowable projects found — choose one</html>"
            else -> "Whole project"
        }
        val showChangeLink = subCount >= 2 || active.isNotBlank()

        val index = project.service<FlowableModelIndexService>().cachedOrNull()
        val indexText = if (index == null) {
            "Not scanned yet — Rebuild scans the project"
        } else {
            val byType = index.allDistinct().groupBy { it.type }
            val counts = ModelType.entries.mapNotNull { t -> byType[t]?.let { t.display to it.size } }
            val top = counts.sortedByDescending { it.second }.take(4)
                .joinToString(" · ") { (name, count) -> "$count $name" }
            val more = (counts.size - 4).takeIf { it > 0 }?.let { " · +$it more" } ?: ""
            "<html><b>${index.distinctCount()}</b> models indexed<br>$top$more</html>"
        }

        val artifacts = base?.let { b ->
            AtlasExplorerFiles.find(b, settings.atlasOutputDir).map { p ->
                val rel = runCatching { b.relativize(p).parent?.toString() ?: "" }.getOrDefault("")
                val modified = runCatching { java.nio.file.Files.getLastModifiedTime(p).toMillis() }.getOrDefault(0L)
                ExplorerArtifact(p, rel, modified)
            }
        }.orEmpty()

        val designText = if (settings.isDesignConfigured()) {
            val lastPull = DesignPullService.lastPullMillis(project)
                ?.let { DateFormatUtil.formatPrettyDateTime(it) } ?: "never"
            "<html>${settings.designBaseUrl} · <b>${settings.designAppKey}</b><br>Last pull: $lastPull</html>"
        } else {
            "Not configured"
        }

        return Snapshot(projectText, showChangeLink, indexText, artifacts, designText)
    }

    private fun apply(snapshot: Snapshot) {
        projectStatus.text = snapshot.projectText
        changeProjectLink?.isVisible = snapshot.showChangeLink
        indexStatus.text = snapshot.indexText
        designStatus.text = snapshot.designText
        val selected = selectedArtifact()?.path
        artifactsModel.replaceAll(snapshot.artifacts)
        selected?.let { keep ->
            snapshot.artifacts.firstOrNull { it.path == keep }
                ?.let { artifactsList.setSelectedValue(it, false) }
        }
    }

    override fun dispose() {}

    companion object {
        private const val WHOLE_PROJECT_LABEL = "Whole project (repository root)"
    }
}
