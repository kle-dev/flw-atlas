package com.flowable.atlas.hub

import com.flowable.atlas.AtlasBuildInfo
import com.flowable.atlas.action.FlowableActionIds
import com.flowable.atlas.action.GenerateModelConstantsAction
import com.flowable.atlas.action.RebuildModelIndexAction
import com.flowable.atlas.design.DesignAppListUi
import com.flowable.atlas.design.DesignClient
import com.flowable.atlas.environment.auth.AtlasCredentials
import com.flowable.atlas.design.DesignPullSelection
import com.flowable.atlas.design.DesignPullService
import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.environment.AtlasCatalog
import com.flowable.atlas.environment.AtlasConnection
import com.flowable.atlas.environment.AtlasConnectionSelection
import com.flowable.atlas.environment.AtlasDesignTarget
import com.flowable.atlas.environment.AtlasEnvironments
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.environment.ConnectionLabels
import com.flowable.atlas.environment.EnvironmentLinks
import com.flowable.atlas.environment.EnvironmentPopup
import com.flowable.atlas.events.AtlasEventsListener
import com.flowable.atlas.explorer.AtlasBrowser
import com.flowable.atlas.explorer.AtlasExplorerFiles
import com.flowable.atlas.explorer.AtlasExplorerOpener
import com.flowable.atlas.explorer.AtlasExplorerStaleness
import com.flowable.atlas.explorer.AtlasGenerationRunner
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.settings.FlowableAtlasConfigurable
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
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
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBColor
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.actionButton
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.SingleAlarm
import com.intellij.util.text.DateFormatUtil
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
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
        /** Detected Flowable sub-projects, root-relative — the picker's choices beside "whole project". */
        val subProjects: List<String>,
        /** The one in use; `""` is the whole repository. */
        val activeSubProject: String,
        /** Set only while a repository holds several and nobody has said which — otherwise blank. */
        val projectNote: String,
        val indexText: String,
        val artifacts: List<ExplorerArtifact>,
        val designText: String,
        val explorerStale: Boolean,
        val browserAvailable: Boolean,
        /** A Design connection resolved — enough to show (and use) the workspace/app pickers. */
        val designServerSet: Boolean,
        val designResolution: AtlasConnectionSelection.Resolution,
        val workResolution: AtlasConnectionSelection.Resolution,
        /** False only before anything at all has been defined — the one state worth its own row. */
        val hasAnyEnvironment: Boolean,
        /** Names the pull link's target, so the button says what it is about to do. */
        val designEnvironmentName: String,
        /** What a pull would fetch right now. */
        val pullSelection: DesignPullSelection,
        /** Where the explorer search looked — what the empty state has to name to be believable. */
        val searchedIn: String,
    )

    /**
     * Which Flowable project Atlas works on — a combo for the same reason the environment rows are one:
     * a line reading *Whole project* with no control beside it answers "what is this?" but not "is this
     * mine to change?", and in a repository holding several apps that second question is the whole
     * point of the row. Closed, it costs the line the label cost, and it replaces the *Change…* link
     * that sat on a second line below it.
     */
    private val projectCombo = ComboBox<String?>().apply {
        renderer = textListCellRenderer { if (it.isNullOrBlank()) "Whole project" else it }
        addActionListener { if (!populatingCombos) chooseSubProject(selectedItem as? String) }
    }

    /** Amber, not red: several projects and no choice yet is a prompt, not a fault. */
    private val projectNote = JBLabel().apply { foreground = JBColor.ORANGE }
    private val noEnvironmentsStatus = JBLabel("No environments yet")

    /**
     * Real combo boxes rather than a label with a "Change…" link.
     *
     * The link opened the same picker and cost the same clicks, but it did not *look* like a choice —
     * it read as a status line with an escape hatch, and the switch is the gesture this whole panel is
     * organised around. A closed combo is the same one line of height and says "this is yours to
     * change" without anyone having to discover it.
     */
    private val designEnvCombo = ComboBox<AtlasConnection?>().apply {
        renderer = connectionRenderer()
        addActionListener { if (!populatingCombos) chooseConnection(ConnectionKind.DESIGN, selectedItem) }
    }
    private val workEnvCombo = ComboBox<AtlasConnection?>().apply {
        renderer = connectionRenderer()
        addActionListener { if (!populatingCombos) chooseConnection(ConnectionKind.WORK, selectedItem) }
    }
    private val designEnvNote = JBLabel().apply { foreground = JBColor.RED }
    private val workEnvNote = JBLabel().apply { foreground = JBColor.RED }

    /** Guards the combo listeners while they are filled programmatically. */
    private var populatingCombos = false
    private var connectionsEmptyRow: com.intellij.ui.dsl.builder.Row? = null
    private var designConnectionRow: com.intellij.ui.dsl.builder.Row? = null
    private var pullLink: javax.swing.JComponent? = null
    private val indexStatus = JBLabel()
    private val designStatus = JBLabel()
    private var staleRow: com.intellij.ui.dsl.builder.Row? = null
    private var openInBrowserLink: javax.swing.JComponent? = null
    private val artifactsModel = CollectionListModel<ExplorerArtifact>()
    private val artifactsList = JBList(artifactsModel).apply {
        // Set from the content on every refresh — see [applyArtifacts]. A fixed eight rows meant a
        // panel that is mostly empty box: the ordinary project generates *one* explorer.
        visibleRowCount = 1
        setEmptyText("No explorer generated yet")
        cellRenderer = object : ColoredListCellRenderer<ExplorerArtifact>() {
            override fun customizeCellRenderer(
                list: JList<out ExplorerArtifact>, value: ExplorerArtifact,
                index: Int, selected: Boolean, hasFocus: Boolean,
            ) {
                // Name plus the tail of its folder, and nothing else: the row lives in a side panel,
                // where a full project-relative path and a timestamp behind it ran off the edge. Both
                // are in the tooltip, which is where a detail nobody scans for belongs.
                icon = AllIcons.Nodes.PpWeb
                append(value.path.fileName.toString(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                shortFolder(value.relative).takeIf { it.isNotEmpty() }
                    ?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                toolTipText = artifactTooltip(value)
            }
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedArtifact()?.let(::openArtifact)
            }
        })
    }

    private val artifactsScroll = JBScrollPane(artifactsList)
    private val explorerHint = JBLabel("No explorer generated yet").apply { foreground = JBColor.GRAY }
    private var explorerListRow: com.intellij.ui.dsl.builder.Row? = null
    private var explorerHintRow: com.intellij.ui.dsl.builder.Row? = null

    // -- Design pull selection (personal override of the configured default) ----------------------

    private val appList = CheckBoxList<DesignClient.App>().apply {
        setCheckBoxListListener { _, _ -> if (!populatingApps) onSelectionEdited() }
        visibleRowCount = 1
    }
    private val appsScroll = JBScrollPane(appList)
    /**
     * The workspace list is a server round-trip, so the combo starts holding the stored key as a
     * placeholder and fetches the real list the first time it is opened — the panel still never calls
     * Design just because it was rendered.
     */
    private val workspaceCombo = ComboBox<DesignClient.Workspace>().apply {
        renderer = textListCellRenderer("none selected") { DesignAppListUi.workspaceLabel(it) }
        addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent) =
                loadWorkspaces(force = false, reveal = true)
            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent) = Unit
            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent) = Unit
        })
        addActionListener {
            if (!populatingCombos) (selectedItem as? DesignClient.Workspace)?.let { selectWorkspace(it.key) }
        }
    }
    /** Stands in for the app list while that list is empty — see [applyAppsRows]. */
    private val appsHint = JBLabel().apply { foreground = JBColor.GRAY }
    private var designWorkspaceRow: com.intellij.ui.dsl.builder.Row? = null
    private var designAppsListRow: com.intellij.ui.dsl.builder.Row? = null
    private var designAppsHintRow: com.intellij.ui.dsl.builder.Row? = null
    /** Guards the checkbox listener while the list is repopulated programmatically. */
    private var populatingApps = false
    /** The live app list is fetched once per workspace, on demand — `gather()` stays network-free. */
    private var fetchedAppsWorkspace: String? = null
    /** Whether an app fetch is in flight, so the hint can say "loading" rather than "none". EDT only. */
    private var loadingApps = false
    /** The picker's workspace list, fetched on first use and kept until the section is reloaded.
     *  EDT-only, like the app list it belongs to — [gather] must not read it. */
    private var fetchedWorkspaces: List<DesignClient.Workspace>? = null

    /**
     * Set by a listener on whatever thread published, honoured later on the EDT. The topic's contract
     * is that a subscriber may only *schedule* work, and [fetchedWorkspaces]/[fetchedAppsWorkspace] are
     * EDT-only state — so the event sets a flag and pokes the alarm, and [applyPullSelection] does the
     * clearing. Without this the Hub kept the previous server's app names after a connection switch,
     * because `loadApps(force = false)` returns early whenever the workspace key is unchanged.
     */
    @Volatile
    private var designCachesStale = false

    private val refreshAlarm = SingleAlarm(::refreshNow, 300, this)

    private fun invalidateDesignListsAndRefresh() {
        designCachesStale = true
        refreshAlarm.cancelAndRequest()
    }

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
            override fun settingsApplied() = refreshAlarm.cancelAndRequest()
            override fun environmentsChanged() = invalidateDesignListsAndRefresh()
            override fun connectionSelectionChanged(kind: ConnectionKind) = invalidateDesignListsAndRefresh()
        })
        refreshAlarm.request()
    }

    /**
     * Openers plus a refresh, and nothing else: the two explorers this plugin has, the environment's own
     * pages, and the panel's own reload. *Generate Atlas Explorer* and *Pull from Design* used to sit
     * here too, and both are already links inside the section whose state they change — a second copy
     * only made the row wide and the choice ambiguous. Doing something to the project belongs next to
     * that thing's state; getting somewhere belongs here.
     */
    private fun buildToolbarGroup(): DefaultActionGroup {
        val am = ActionManager.getInstance()
        val group = DefaultActionGroup()
        listOf(
            FlowableActionIds.OPEN_ATLAS_EXPLORER,
            FlowableActionIds.OPEN_EXPRESSION_PLAYGROUND,
        ).forEach { id -> am.getAction(id)?.let(group::add) }
        group.add(openEnvironmentAction())
        group.add(object : AnAction("Refresh", "Refresh the Atlas Hub", AllIcons.Actions.Refresh), DumbAware {
            override fun actionPerformed(e: AnActionEvent) = refreshAlarm.cancelAndRequest()
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        })
        return group
    }

    /**
     * Hands an environment's own pages to the browser — Design, the app, Control, Hub.
     *
     * The Hub already knows every stage's addresses; without this they were still bookmarks, and
     * "which one was QA's Control again?" was answered in the browser rather than here. It is a chooser
     * rather than a row of links because a link per environment per product is a grid, and the panel is
     * a few hundred pixels wide.
     *
     * It follows **no** pointer. The Design pull and the playground each point at an environment, and a
     * third rule about which one this opens would be one more thing that can silently be wrong — this
     * asks, every time, and the asking is one keystroke with speed search.
     */
    private fun openEnvironmentAction(): AnAction =
        object : AnAction(
            "Open Environment in Browser",
            "Open a Flowable Design, Work, Control or Hub page of one of your environments",
            AllIcons.Ide.Link,
        ), DumbAware {
            override fun actionPerformed(e: AnActionEvent) {
                JBPopupFactory.getInstance()
                    .createActionGroupPopup(
                        "Open in Browser", environmentLinkActions(), e.dataContext,
                        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true,
                    )
                    .showCenteredInCurrentWindow(project)
            }

            override fun update(e: AnActionEvent) {
                // Both halves matter: nothing to open, and nowhere to open it — under Remote Dev on a
                // headless host a browse would simply do nothing at all.
                e.presentation.isEnabled = AtlasBrowser.canOpenUrls() && !EnvironmentLinks.isEmpty(
                    AtlasCatalog.environments(project), AtlasCatalog.connections(project),
                )
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        }

    /** One separator per environment, one item per address — the environment names the group so the
     *  items can be just "Control", which is what the reader is scanning for. */
    private fun environmentLinkActions(): DefaultActionGroup {
        val group = DefaultActionGroup()
        EnvironmentLinks.grouped(
            AtlasCatalog.environments(project), AtlasCatalog.connections(project),
        ).forEach { environment ->
            group.addSeparator(environment.environment.name.ifBlank { "unnamed" })
            environment.links.forEach { connection ->
                group.add(
                    object : AnAction(
                        connection.kind.display,
                        connection.baseUrl,
                        // A padlock, not a prompt: opening a page changes nothing, so PROD is shown
                        // rather than guarded — but it is shown, so nobody clicks it by mistake.
                        if (connection.requiresConfirmation) AllIcons.Nodes.Padlock else null,
                    ), DumbAware {
                        override fun actionPerformed(e: AnActionEvent) =
                            AtlasBrowser.open(connection.baseUrl, project)

                        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                    },
                )
            }
        }
        return group
    }

    private fun buildContent() = panel {
        group("Flowable Project") {
            row {
                cell(projectCombo).align(AlignX.FILL).resizableColumn()
                cell(projectNote)
            }
        }
        group("Model Index") {
            row { cell(indexStatus) }
            row {
                link("Search Models…") { invokeAction(FlowableActionIds.SEARCH_MODELS) }
                link("Rebuild") { RebuildModelIndexAction.rebuild(project) }
                link("Generate Constants…") { GenerateModelConstantsAction.generate(project) }
            }
        }
        group("Atlas Explorer") {
            explorerListRow = row {
                cell(artifactsScroll).align(AlignX.FILL)
            }.visible(false)
            // A list box holding one centred "nothing here" line is most of this panel's height spent
            // saying nothing. One grey line where every other line starts says the same thing.
            explorerHintRow = row { cell(explorerHint) }
            row {
                link("Generate…") { invokeAction(FlowableActionIds.GENERATE_ATLAS_EXPLORER) }
                // Selected entry, or the newest one when nothing is selected (the list is sorted
                // most-recently-modified first). Hidden while there is nothing to open — it used to
                // fall through to the generate dialog, which is not what a link called "Open" does;
                // "Generate…" sits right beside it.
                link("Open in Browser") {
                    (selectedArtifact() ?: artifactsModel.items.firstOrNull())?.let { AtlasBrowser.open(it.path) }
                }.applyToComponent { openInBrowserLink = this }
            }
            // Shown when a model in scope is newer than the newest generated page — after a Design pull,
            // a git pull, an unzipped export, a hand edit alike (AtlasExplorerStaleness). The index
            // follows such changes on its own; the generated HTML does not, so offer the regenerate here.
            staleRow = row {
                label("Models changed since the last generation.")
                link("Regenerate Atlas Explorer") { AtlasGenerationRunner.regenerate(project) }
            }.visible(false)
        }
        // One section, one task, in the order the work is actually done: which environment, which
        // workspace in it, which apps, pull. Splitting the first two into a separate "Connections"
        // group put half the sequence somewhere else and left this one saying "choose a workspace"
        // with no way to choose it.
        group("Flowable Design") {
            connectionsEmptyRow = row {
                cell(noEnvironmentsStatus)
                link("Add an environment…") { invokeAction(FlowableActionIds.MANAGE_ENVIRONMENTS) }
            }.visible(false)
            designConnectionRow = row("Environment:") {
                cell(designEnvCombo).align(AlignX.FILL).resizableColumn()
                cell(designEnvNote)
                // The reload icon re-reads both server lists, so it belongs with the server it re-reads.
                actionButton(reloadDesignListsAction())
            }.visible(false)
            designWorkspaceRow = row("Workspace:") {
                cell(workspaceCombo).align(AlignX.FILL).resizableColumn()
            }.visible(false)
            // Which apps a pull fetches — starting from the configured default; ticking them here is a
            // personal override that never touches the shared settings file.
            designAppsListRow = row {
                cell(appsScroll).align(AlignX.FILL)
            }.visible(false)
            // With no apps to tick, the list would be a tall empty box holding a centred label that a
            // narrow tool window clips. A line of text where every other line in the panel starts says
            // the same thing, and costs no height.
            designAppsHintRow = row { cell(appsHint) }.visible(false)
            row {
                // Pulls exactly what is ticked above — the Tools-menu action resolves to the same
                // effective selection, so the two can never disagree. The link names its target, so
                // "which server is this about to hit?" is answered without opening anything.
                link("Pull from Design") { pullSelected() }
                    .applyToComponent { pullLink = this }
                link("Manage environments…") { invokeAction(FlowableActionIds.MANAGE_ENVIRONMENTS) }
            }
            row { cell(designStatus) }
        }
        // The runtime connection has one interesting fact in a status panel — which environment — and it
        // belongs to the playground rather than to the pull, so it lives beside the thing that uses it.
        group("Expression Playground") {
            row("Environment:") {
                cell(workEnvCombo).align(AlignX.FILL).resizableColumn()
                cell(workEnvNote)
            }
            row {
                link("Open Playground") { invokeAction(FlowableActionIds.OPEN_EXPRESSION_PLAYGROUND) }
            }
        }
        separator()
        row {
            link("Settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, FlowableAtlasConfigurable::class.java)
            }
        }
        row { comment("Flowable Atlas ${atlasVersion()}") }
    }

    /** The running plugin's version (what the user sees in Settings › Plugins); falls back to the baked
     * :core build version, which is the same Gradle version, so any mismatch would itself signal drift. */
    private fun atlasVersion(): String =
        PluginManagerCore.getPlugin(PluginId.getId("com.flowable.atlas"))?.version ?: AtlasBuildInfo.VERSION

    private fun invokeAction(id: String) {
        val action = ActionManager.getInstance().getAction(id) ?: return
        val context = DataManager.getInstance().getDataContext(this)
        val event = AnActionEvent.createEvent(action, context, null, "AtlasHub", ActionUiKind.NONE, null)
        ActionUtil.performAction(action, event)
    }

    /**
     * Switches the active Flowable sub-project. Storing it also records that a choice was *made*, which
     * is what retires the "choose one" note — `""` alone cannot tell a deliberate whole-repository from
     * a default nobody looked at.
     */
    private fun chooseSubProject(relPath: String?) {
        AtlasProjectRootService.getInstance(project).setActiveSubProject(relPath.orEmpty())
    }

    private fun selectedArtifact(): ExplorerArtifact? = artifactsList.selectedValue

    private fun artifactTooltip(artifact: ExplorerArtifact): String {
        val where = artifact.relative.ifEmpty { "." }
        val when_ = artifact.modified.takeIf { it > 0 }
            ?.let { ", generated ${DateFormatUtil.formatPrettyDateTime(it)}" } ?: ""
        return "$where$when_"
    }

    private fun openArtifact(artifact: ExplorerArtifact) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(artifact.path)
        when {
            vf != null && JBCefApp.isSupported() -> AtlasExplorerOpener.openInIde(project, vf)
            AtlasBrowser.canOpenFiles() -> AtlasBrowser.open(artifact.path)   // JCEF unavailable → external browser
            else -> NotificationGroupManager.getInstance().getNotificationGroup("Flowable Atlas")
                .createNotification(
                    "Cannot open the explorer here",
                    "This IDE has neither an embedded browser nor a way to launch one (a Remote Dev backend, say). " +
                        "Open ${artifact.path.fileName} from the client machine instead.",
                    NotificationType.WARNING,
                )
                .notify(project)
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
        // The one thing the picker cannot say by itself: that a choice is *outstanding*. Several
        // projects and nobody has picked means Atlas is indexing the whole repository by default, which
        // is a decision the user never made.
        val projectNote = if (subCount >= 2 && !chosen && active.isBlank()) "$subCount found — choose one" else ""

        val indexService = project.service<FlowableModelIndexService>()
        val index = indexService.cachedOrNull()
        // No index yet? Ask for one (a single background build, joined by every other asker) — the
        // panel redraws on modelIndexUpdated, so "scanning" resolves itself without a click.
        if (index == null) indexService.ensureBuilding()
        val indexText = if (index == null) {
            "Scanning the project…"
        } else {
            val byType = index.allDistinct().groupBy { it.type }
            val counts = ModelType.entries.mapNotNull { t -> byType[t]?.let { t.display to it.size } }
            val top = counts.sortedByDescending { it.second }.take(4)
                .joinToString(" · ") { (name, count) -> "$count $name" }
            val more = (counts.size - 4).takeIf { it > 0 }?.let { " · +$it more" } ?: ""
            val scanned = index.builtAtMillis.takeIf { it > 0 }
                ?.let { "<br>Last scanned: ${DateFormatUtil.formatPrettyDateTime(it)}" } ?: ""
            "<html><b>${index.distinctCount()}</b> models indexed<br>$top$more$scanned</html>"
        }

        val artifacts = base?.let { b ->
            AtlasExplorerFiles.find(b, settings.atlasOutputDir).map { p ->
                val rel = runCatching { b.relativize(p).parent?.toString() ?: "" }.getOrDefault("")
                val modified = runCatching { java.nio.file.Files.getLastModifiedTime(p).toMillis() }.getOrDefault(0L)
                ExplorerArtifact(p, rel, modified)
            }
        }.orEmpty()

        val designResolution = AtlasConnectionSelection.resolution(project, ConnectionKind.DESIGN)
        val workResolution = AtlasConnectionSelection.resolution(project, ConnectionKind.WORK)
        val designConnection = (designResolution as? AtlasConnectionSelection.Resolution.Selected)?.connection

        val lastPullMillis = DesignPullService.lastPullMillis(project)
        val pullSelection = designConnection?.let { AtlasDesignTarget.selection(project, it) }
            ?: DesignPullSelection.EMPTY
        // The server has its own row above now, so this line says only what a pull would *do*. Dropping
        // the base URL from it is what keeps the panel inside a few hundred pixels.
        // Only what the rows above cannot already show. The ticked apps are visible in the list right
        // there, so repeating them here would be the panel arguing with itself.
        val designText = if (designConnection != null) {
            val lastPull = lastPullMillis?.let { DateFormatUtil.formatPrettyDateTime(it) } ?: "never"
            "<html>Last pull: $lastPull</html>"
        } else {
            ""
        }
        // Stale when the last pull happened after the newest generated explorer artifact.
        val explorerStale = AtlasExplorerStaleness.isStale(
            artifacts.map { it.modified }, AtlasExplorerStaleness.latestModelChange(project),
        )

        return Snapshot(
            detected?.map { it.relPath }.orEmpty(), active, projectNote,
            indexText, artifacts, designText,
            explorerStale, AtlasBrowser.canOpenFiles(),
            designConnection != null, designResolution, workResolution,
            AtlasCatalog.environments(project).isNotEmpty(),
            designConnection?.environmentName.orEmpty(),
            pullSelection,
            searchedIn = listOfNotNull(active.ifBlank { null }, settings.atlasOutputDir)
                .joinToString("/") + "/",
        )
    }

    private fun apply(snapshot: Snapshot) {
        applyConnections(snapshot)
        applyProject(snapshot)
        indexStatus.text = snapshot.indexText
        designStatus.text = snapshot.designText
        staleRow?.visible(snapshot.explorerStale)
        openInBrowserLink?.isVisible = snapshot.browserAvailable && snapshot.artifacts.isNotEmpty()
        applyArtifacts(snapshot)
        applyPullSelection(snapshot)
    }

    /**
     * Fills the project picker. The active sub-project is always in the list even when detection has
     * not run yet, so the row can never show *Whole project* while Atlas is scoped to something else.
     */
    private fun applyProject(snapshot: Snapshot) {
        val items = (listOf("") + snapshot.subProjects + snapshot.activeSubProject).distinct()
        populatingCombos = true
        try {
            projectCombo.model = DefaultComboBoxModel<String?>().apply { items.forEach { addElement(it) } }
            projectCombo.selectedItem = snapshot.activeSubProject
        } finally {
            populatingCombos = false
        }
        projectNote.text = snapshot.projectNote
        projectCombo.toolTipText = when {
            snapshot.activeSubProject.isNotBlank() -> snapshot.activeSubProject
            snapshot.subProjects.isEmpty() -> "Atlas analyses the whole repository — no separate Flowable projects were detected"
            else -> "Atlas analyses the whole repository; pick one of the ${snapshot.subProjects.size} " +
                "Flowable projects in it to narrow that"
        }
    }

    /** The generated pages, sized to how many there are — one project usually has exactly one. */
    private fun applyArtifacts(snapshot: Snapshot) {
        val selected = selectedArtifact()?.path
        artifactsModel.replaceAll(snapshot.artifacts)
        selected?.let { keep ->
            snapshot.artifacts.firstOrNull { it.path == keep }
                ?.let { artifactsList.setSelectedValue(it, false) }
        }
        artifactsList.visibleRowCount = snapshot.artifacts.size.coerceIn(1, MAX_VISIBLE_ROWS)
        artifactsScroll.revalidate()
        explorerListRow?.visible(snapshot.artifacts.isNotEmpty())
        explorerHintRow?.visible(snapshot.artifacts.isEmpty())
        // Not "nothing has been generated" — this panel cannot know that. *Generate…* writes wherever
        // you point it, and the search is scoped to the active Flowable project's output folder, so a
        // page saved to the desktop or to a sibling project is invisible here and the old wording
        // called that "not generated yet". Naming the folder turns a wrong claim into a findable
        // mismatch.
        // Plain text, not HTML: the value is a user-typed folder name, and a label that renders markup
        // would have to escape it — a question this line does not need to have.
        explorerHint.text = "No explorer in ${snapshot.searchedIn} yet"
        explorerHint.toolTipText = "Generate… writes wherever you point it; the Hub lists this folder " +
            "and a shallow scan of the Flowable project above."
    }

    /**
     * The two connection rows. Nothing here prints a base URL: this panel is read in a side stripe a
     * few hundred pixels wide, and a URL is what forces horizontal scrolling. Everything a reader
     * occasionally wants — the full name, the URL, what *protected* means — is in the tooltip.
     */
    private fun applyConnections(snapshot: Snapshot) {
        connectionsEmptyRow?.visible(!snapshot.hasAnyEnvironment)
        designConnectionRow?.visible(snapshot.hasAnyEnvironment)
        fillConnections(
            designEnvCombo, designEnvNote,
            AtlasCatalog.connections(project, ConnectionKind.DESIGN), snapshot.designResolution,
        )
        fillConnections(
            workEnvCombo, workEnvNote,
            AtlasCatalog.connections(project, ConnectionKind.WORK), snapshot.workResolution,
        )
        (pullLink as? com.intellij.ui.components.ActionLink)?.text =
            if (snapshot.designEnvironmentName.isBlank()) "Pull from Design"
            else "Pull from ${snapshot.designEnvironmentName}"
    }

    /**
     * Fills one environment combo. A pointer that no longer resolves gets its own note rather than
     * quietly reading "not set": the two need different fixes, and silently swapping in another
     * environment is the one thing this must never do.
     */
    private fun fillConnections(
        combo: ComboBox<AtlasConnection?>,
        note: JBLabel,
        available: List<AtlasConnection>,
        resolution: AtlasConnectionSelection.Resolution,
    ) {
        val selected = (resolution as? AtlasConnectionSelection.Resolution.Selected)?.connection
        populatingCombos = true
        try {
            combo.model = DefaultComboBoxModel<AtlasConnection?>().apply {
                addElement(null)                    // "not set" is a real, choosable state
                available.forEach { addElement(it) }
            }
            combo.selectedItem = selected?.let { current -> available.firstOrNull { it.id == current.id } }
        } finally {
            populatingCombos = false
        }
        combo.toolTipText = ConnectionLabels.tooltip(
            if (combo === designEnvCombo) ConnectionKind.DESIGN else ConnectionKind.WORK,
            resolution,
        )
        note.text = if (resolution is AtlasConnectionSelection.Resolution.Dangling) "was removed" else ""
    }

    /** Renders an environment with a padlock when it is protected; `null` is the "not set" entry. */
    private fun connectionRenderer() =
        listCellRenderer<AtlasConnection?> {
            if (value?.requiresConfirmation == true) icon(AllIcons.Nodes.Padlock)
            text(value?.let { ConnectionLabels.pickerItem(it) } ?: "not set")
        }

    private fun chooseConnection(kind: ConnectionKind, item: Any?) {
        val connection = item as? AtlasConnection
        // "not set" is a choice the user made, not the absence of one — so it is *stored* as such.
        // Merely unsetting the pointer let the single-environment fallback answer again, and the panel
        // below went on showing that environment's workspace and apps.
        if (connection == null) AtlasConnectionSelection.selectNone(project, kind)
        else AtlasConnectionSelection.select(project, kind, connection.id)
    }

    // -- pull selection ---------------------------------------------------------------------------

    /** Renders the workspace and the app list from the effective selection. Seeds key-only
     *  placeholders so the panel shows the selection without a network call; real names arrive with
     *  [loadApps] and — after an environment switch — [loadWorkspaces]. */
    private fun applyPullSelection(snapshot: Snapshot) {
        // True exactly on the passes that follow a switch (environment picked, catalog edited): the one
        // moment both server lists are known to be wrong, and the one moment it is right to go and ask.
        val switched = designCachesStale
        if (switched) {
            designCachesStale = false
            fetchedWorkspaces = null       // the picker must show the new server's list
            fetchedAppsWorkspace = null    // …and the app list must be re-fetched, same key or not
        }
        designWorkspaceRow?.visible(snapshot.designServerSet)
        if (!snapshot.designServerSet) {
            // "not set" is a state, not a pause: leaving the previous environment's workspace and apps
            // in the (hidden) controls means the next time they are shown, they are shown wrong.
            fetchedAppsWorkspace = null
            fillWorkspaces("")
            populatingApps = true
            try {
                DesignAppListUi.populateApps(appList, emptyList(), emptySet())
            } finally {
                populatingApps = false
            }
            designAppsListRow?.visible(false)
            designAppsHintRow?.visible(false)
            return
        }
        val workspaceKey = snapshot.pullSelection.workspaceKey
        fillWorkspaces(workspaceKey)
        // The stored key alone is a placeholder nobody can choose from. Picking an environment is a
        // gesture that deserves the real list behind it — so the fetch happens here rather than waiting
        // for the user to discover that the combo has to be opened twice.
        if (switched) loadWorkspaces(force = true, reveal = false)
        val checked = snapshot.pullSelection.appKeys.toSet()
        val known = (0 until appList.model.size).mapNotNull { appList.getItemAt(it) }
        // Keep the fetched apps (real names/versions) while the workspace still matches. A switched
        // workspace must drop them entirely: the previous workspace's apps do not exist in the new one,
        // so carrying their keys over as placeholders would show a list of apps that cannot be pulled.
        val items = if (known.isNotEmpty() && fetchedAppsWorkspace == workspaceKey) known
            else DesignAppListUi.placeholders(checked.sorted())
        populatingApps = true
        try {
            DesignAppListUi.populateApps(appList, items, checked)
        } finally {
            populatingApps = false
        }
        if (fetchedAppsWorkspace != workspaceKey) loadApps(force = false)
        applyAppsRows(workspaceKey)
    }

    /** Shows either the checkbox list or a one-line hint — never an empty list box. */
    private fun applyAppsRows(workspaceKey: String) {
        val hasApps = appList.model.size > 0
        // Sized to the workspace: one app is one row, and a workspace with twenty scrolls at the cap
        // rather than pushing everything below it off the panel.
        appList.visibleRowCount = appList.model.size.coerceIn(1, MAX_VISIBLE_ROWS)
        appsScroll.revalidate()
        designAppsListRow?.visible(hasApps)
        designAppsHintRow?.visible(!hasApps)
        if (hasApps) return
        appsHint.text = when {
            workspaceKey.isBlank() -> "Choose a workspace to list its apps."
            loadingApps -> "Loading apps…"
            else -> "No apps in this workspace."
        }
    }

    /** Fetches the workspace's apps once (or on demand) so the checkboxes show real names/versions.
     *  Never called from [gather] — the snapshot pass stays network-free. */
    private fun loadApps(force: Boolean) {
        if (AtlasConnectionSelection.selected(project, ConnectionKind.DESIGN) == null) return
        val workspaceKey = currentSelection().workspaceKey
        if (!force && fetchedAppsWorkspace == workspaceKey) return
        fetchedAppsWorkspace = workspaceKey
        // No workspace picked yet: there is nothing to list, and an empty path segment would only 404.
        if (workspaceKey.isBlank()) return
        loadingApps = true
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            // Not an early return: an unreadable credential has to travel back to the EDT like any other
            // failure, or the hint below stays on "Loading apps…" for the rest of the session.
            val conn = designConnection()
            val result = conn?.let { DesignClient.listApps(it, workspaceKey) }
            val apps = (result as? DesignClient.Result.Success)?.value
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed || fetchedAppsWorkspace != workspaceKey) return@invokeLater
                loadingApps = false
                // A failed fetch leaves the list as it was; only a successful one replaces it — and it
                // is not remembered as a fetch, so the next refresh or Reload tries again instead of
                // treating an empty list as this workspace's answer.
                if (apps == null) fetchedAppsWorkspace = null
                if (apps != null) {
                    val checked = DesignAppListUi.checkedAppKeys(appList).toSet()
                        .ifEmpty { currentSelection().appKeys.toSet() }
                    populatingApps = true
                    try {
                        DesignAppListUi.populateApps(appList, apps, checked)
                    } finally {
                        populatingApps = false
                    }
                }
                applyAppsRows(workspaceKey)
            }, ModalityState.any())
        }
    }

    /** The connection a list fetch runs with, or null when nothing would authenticate it (a cleared or
     *  locked keychain entry, and no captured browser session). Reads the PasswordSafe, so it must stay
     *  off the EDT. */
    private fun designConnection(): DesignClient.Connection? {
        val selected = AtlasConnectionSelection.selected(project, ConnectionKind.DESIGN) ?: return null
        val auth = try {
            AtlasCredentials.contextFor(selected.baseUrl, selected.authMode, selected.username)
        } catch (pce: ProcessCanceledException) {
            throw pce                      // a cancelled action is not a failure
        } catch (e: Exception) {
            null
        } ?: return null
        if (auth.isEmpty) return null
        return DesignClient.Connection(selected.baseUrl, auth)
    }

    /** The section's one reload control: re-reads both server lists. Not a registered action — it
     *  belongs to this panel, like the toolbar's Refresh, which only re-gathers local state. */
    private fun reloadDesignListsAction(): AnAction =
        object : AnAction(
            "Reload from Flowable Design",
            "Re-read the workspace and app lists from the Flowable Design server",
            AllIcons.Actions.Refresh,
        ), DumbAware {
            override fun actionPerformed(e: AnActionEvent) {
                fetchedWorkspaces = null   // so the combo shows the server's current list
                loadWorkspaces(force = true, reveal = false)
                loadApps(force = true)
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        }

    /**
     * Fills the workspace combo. Until the list has been fetched it holds the stored key as a single
     * placeholder, so the selection is visible without the panel ever calling Design just because it
     * was rendered; the real names arrive when the combo is first opened.
     */
    private fun fillWorkspaces(selectedKey: String) {
        val fetched = fetchedWorkspaces
        val items = when {
            fetched != null -> fetched
            selectedKey.isBlank() -> emptyList()
            else -> listOf(DesignClient.Workspace(selectedKey, selectedKey))
        }
        populatingCombos = true
        try {
            workspaceCombo.model = DefaultComboBoxModel<DesignClient.Workspace>().apply {
                items.forEach { addElement(it) }
            }
            workspaceCombo.selectedItem = items.firstOrNull { it.key == selectedKey }
        } finally {
            populatingCombos = false
        }
        workspaceCombo.toolTipText = items.firstOrNull { it.key == selectedKey }
            ?.let { DesignAppListUi.workspaceLabel(it) } ?: selectedKey.ifBlank { null }
    }

    /**
     * Reads the server's workspace list, once, unless [force]. Never on a plain render — a panel nobody
     * touched must not cost a round-trip — but on the two gestures that invalidate the list: opening the
     * picker, and switching environment.
     *
     * [reveal] is the difference between those two. A click on the closed combo has to end with the list
     * open, or the user clicks twice for one list; a fetch nobody asked for must not throw a popup over
     * the panel, so the switch and the reload button pass false.
     */
    private fun loadWorkspaces(force: Boolean, reveal: Boolean) {
        if (!force && fetchedWorkspaces != null) return
        val selected = AtlasConnectionSelection.selected(project, ConnectionKind.DESIGN) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val result = designConnection()?.let { DesignClient.listWorkspaces(it) }
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                when {
                    // An unreadable keychain entry and a server error need different fixes, so they do
                    // not share one message.
                    result == null -> notifyDesignProblem(
                        "No Flowable Design credentials could be read for ${selected.baseUrl}.",
                    )
                    result is DesignClient.Result.Failed -> notifyDesignProblem(result.message)
                    result is DesignClient.Result.Success && result.value.isEmpty() ->
                        notifyDesignProblem("No workspaces are visible for this user.")
                    result is DesignClient.Result.Success -> {
                        fetchedWorkspaces = result.value
                        fillWorkspaces(currentSelection().workspaceKey)
                        // Reopen, so the click that asked for the list actually shows it.
                        if (reveal && !workspaceCombo.isPopupVisible) workspaceCombo.showPopup()
                    }
                }
            }, ModalityState.any())
        }
    }

    /** Store the picked workspace. Apps do not carry over — the next workspace does not have them. */
    private fun selectWorkspace(workspaceKey: String) {
        val current = currentSelection()
        if (workspaceKey == current.workspaceKey) return
        store(DesignPullSelection.withWorkspace(current, workspaceKey))
    }

    /** A checkbox was toggled — that *is* the project's selection now, not a copy of it. */
    private fun onSelectionEdited() {
        store(DesignPullSelection(currentSelection().workspaceKey, DesignAppListUi.checkedAppKeys(appList)))
    }

    /**
     * Writes the selection into the project settings, under the selected environment's name. Directly:
     * with the picker here and the value in a settings page, "is this the setting or my copy of it?"
     * had no answer on screen, so there is only the setting now.
     */
    private fun store(selection: DesignPullSelection) {
        val connection = AtlasConnectionSelection.selected(project, ConnectionKind.DESIGN) ?: return
        FlowableAtlasProjectSettings.getInstance(project).pullTarget(connection.environmentName).also {
            it.workspaceKey = selection.workspaceKey
            it.appKeys = selection.appKeys.toMutableList()
        }
        refreshAlarm.cancelAndRequest()
    }

    /** The Hub is a status panel, not a dialog, so a failed list fetch has no inline place to land —
     *  it surfaces as the plugin's usual balloon, with the fix one click away. */
    private fun notifyDesignProblem(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification("Flowable Design", message, NotificationType.WARNING)
            .addAction(NotificationAction.createSimple("Configure…") {
                invokeAction(FlowableActionIds.MANAGE_ENVIRONMENTS)
            })
            .notify(project)
    }


    /** Pull what is ticked; an unconfigured connection falls back to the action's setup flow. */
    private fun pullSelected() {
        // No connection chosen → the action's own flow, which offers the picker or the settings page;
        // with one chosen but nothing ticked, the pull itself says so, which is more useful than
        // reopening Settings.
        if (AtlasConnectionSelection.selected(project, ConnectionKind.DESIGN) == null) {
            invokeAction(FlowableActionIds.PULL_FROM_DESIGN)
            return
        }
        val selection = currentSelection()
        project.service<DesignPullService>().pullInBackground(selection.workspaceKey, selection.appKeys)
    }

    private fun currentSelection(): DesignPullSelection =
        AtlasConnectionSelection.selected(project, ConnectionKind.DESIGN)
            ?.let { AtlasDesignTarget.selection(project, it) }
            ?: DesignPullSelection.EMPTY

    override fun dispose() {}

    /** Gather + apply synchronously — for tests, which cannot wait on the pooled refresh. */
    internal fun refreshForTest() = apply(gather())

    /** For tests: the refresh runs behind a 300 ms alarm no test can fast-forward, so they assert the
     *  flag the listener sets synchronously instead of a repaint they would have to wait for. */
    internal val designCachesStaleForTest: Boolean get() = designCachesStale

    /** For tests: the rendered connection row for [kind]. */
    internal fun connectionLineForTest(kind: ConnectionKind): String {
        val combo = if (kind == ConnectionKind.DESIGN) designEnvCombo else workEnvCombo
        val note = if (kind == ConnectionKind.DESIGN) designEnvNote else workEnvNote
        val name = (combo.selectedItem as? AtlasConnection)?.let { ConnectionLabels.pickerItem(it) } ?: "not set"
        return if (note.text.isBlank()) name else "$name ${note.text}"
    }

    /** For tests: the pull link's text, which names the environment it would pull from. */
    internal fun pullLinkTextForTest(): String =
        (pullLink as? com.intellij.ui.components.ActionLink)?.text.orEmpty()

    /** For tests: whether the empty-state row is the one showing. */
    internal val hasAnyEnvironmentForTest: Boolean
        get() = AtlasCatalog.environments(project).isNotEmpty()

    /** Pick a workspace the way the popup's callback does — for tests, which have no popup. */
    internal fun selectWorkspaceForTest(workspaceKey: String) = selectWorkspace(workspaceKey)

    /** Pick the combo's "not set" entry the way a click does — for tests, which have no combo. */
    internal fun chooseNoEnvironmentForTest(kind: ConnectionKind) = chooseConnection(kind, null)

    /** For tests: the workspace the picker is showing, or null when it shows none. */
    internal fun workspaceKeyForTest(): String? =
        (workspaceCombo.selectedItem as? DesignClient.Workspace)?.key

    /** For tests: what the project picker is offering, `""` first. */
    internal fun projectItemsForTest(): List<String> =
        (0 until projectCombo.itemCount).map { projectCombo.getItemAt(it).orEmpty() }

    /** Pick a Flowable project the way the combo does — for tests, which have no combo. */
    internal fun chooseProjectForTest(relPath: String?) = chooseSubProject(relPath)

    /** For tests: the empty-state line under Atlas Explorer, which has to name where it looked. */
    internal fun explorerHintForTest(): String = explorerHint.text

    /** For tests: how many rows the two lists reserve — the panel's height budget, in one number. */
    internal fun listRowsForTest(): Pair<Int, Int> = artifactsList.visibleRowCount to appList.visibleRowCount

    /** For tests: the app keys the checkbox list is offering. */
    internal fun appKeysForTest(): List<String> =
        (0 until appList.model.size).mapNotNull { appList.getItemAt(it)?.key }

    companion object {
        /** How tall a list in this panel may grow before it scrolls. The Hub lives in a side stripe
         *  shared by five sections; past this, one of them owns the panel. */
        private const val MAX_VISIBLE_ROWS = 8

        /** The last two segments of a project-relative folder, `…/` prefixed when more were dropped. */
        internal fun shortFolder(relative: String): String {
            val parts = relative.split('/', '\\').filter { it.isNotEmpty() }
            return when {
                parts.isEmpty() -> ""
                parts.size <= 2 -> parts.joinToString("/")
                else -> "…/" + parts.takeLast(2).joinToString("/")
            }
        }
        private const val GROUP_ID = "Flowable Atlas"

        /** A generated explorer is stale when something was pulled after its newest artifact was written. */
        /** The predicate lives in [AtlasExplorerStaleness] now (shared with the editor banner). */
        internal fun isExplorerStale(artifactMtimes: List<Long>, changedAt: Long?): Boolean =
            AtlasExplorerStaleness.isStale(artifactMtimes, changedAt)
    }
}
