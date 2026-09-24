package com.flowable.atlas.hub.sections

import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.action.FlowableActionIds
import com.flowable.atlas.design.DesignAppListUi
import com.flowable.atlas.design.DesignClient
import com.flowable.atlas.design.DesignPullSelection
import com.flowable.atlas.design.DesignPullService
import com.flowable.atlas.environment.AtlasConnectionSelection
import com.flowable.atlas.environment.AtlasDesignTarget
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.environment.auth.AtlasCredentials
import com.flowable.atlas.hub.EnvironmentPicker
import com.flowable.atlas.hub.HubAge
import com.flowable.atlas.hub.HubLayout
import com.flowable.atlas.hub.HubLists
import com.flowable.atlas.hub.HubText
import com.flowable.atlas.hub.HubSnapshot
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * The whole pull, top to bottom, in the order the work is done: which environment, which workspace in
 * it, which apps, pull. Always the same four rows — the environment combo disables itself and says "no
 * environments yet" rather than swapping the row for a hint, the workspace combo disables itself while
 * no environment is chosen, and the app list is either a list or one grey line — so switching state
 * moves nothing below it. The section used to grow and shrink by four rows with its mood.
 *
 * Fetching is on demand and never on a plain render: the workspace list when the picker is opened or the
 * environment switched, the app list when the workspace changes. The toolbar's *Refresh* calls
 * [invalidate], which makes the next pass re-fetch both; the section's own reload button, which shared
 * the toolbar's icon and meant something else, is gone. A fetch that fails says so under the workspace
 * picker, with *Retry* beside it — it used to be only a balloon, gone by the time anyone looked at the
 * combo that had not filled.
 *
 * One control per row, and the *Pull* button as wide as the section: it names its target, and an
 * environment called *DEMO-ACCEPTANCE-EU-WEST* made a button-and-date row wider than the stripe.
 */
internal class DesignPullSection(private val host: HubHost) : HubSection {

    override val id = "design"
    override val title: String get() = message("hub.section.design")

    private val project get() = host.project
    val environment = EnvironmentPicker(project, ConnectionKind.DESIGN)

    private var workspacePlaceholder = message("hub.design.workspace.none")
    /**
     * The workspace list is a server round-trip, so the combo starts holding the stored key as a
     * placeholder and fetches the real list the first time it is opened — the panel still never calls
     * Design just because it was rendered.
     */
    private val workspaceCombo = HubLayout.narrow(ComboBox<DesignClient.Workspace>()).apply {
        renderer = listCellRenderer<DesignClient.Workspace?> {
            text(value?.let { DesignAppListUi.workspaceLabel(it) } ?: workspacePlaceholder)
        }
        addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) = loadWorkspaces(force = false, reveal = true)
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) = Unit
            override fun popupMenuCanceled(e: PopupMenuEvent) = Unit
        })
        addActionListener {
            if (!populatingCombos) (selectedItem as? DesignClient.Workspace)?.let { selectWorkspace(it.key) }
        }
    }

    /** Which apps a pull fetches — starting from the stored selection; ticking here *is* the setting. */
    private val appList = object : CheckBoxList<DesignClient.App>() {
        // One fact per row — the name; key and version are what the tooltip is for.
        override fun getToolTipText(e: MouseEvent): String? {
            val i = locationToIndex(e.point)
            return if (i >= 0) getItemAt(i)?.let(DesignAppListUi::appTooltip) else null
        }
        // As wide as the section, not as its longest app name (see HubLayout.list).
        override fun getScrollableTracksViewportWidth(): Boolean = true
    }.apply {
        setCheckBoxListListener { _, _ -> if (!populatingApps) onSelectionEdited() }
        visibleRowCount = 1
    }
    private val appsScroll = HubLayout.listScroll(appList)
    /** Stands in for the app list while that list is empty — never an empty list box. */
    private val appsHint = HubText()
    /** Why the last list fetch failed, under the picker it failed for; hidden while there is nothing to say. */
    private val problem = HubText()
    private val lastPull = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private var manageRow: Row? = null
    private var problemRow: Row? = null
    private var problemLinksRow: Row? = null
    private var appsListRow: Row? = null
    private var appsHintRow: Row? = null
    private lateinit var pullButton: JButton

    /** Guards the combo listeners while they are filled programmatically. */
    private var populatingCombos = false
    /** Guards the checkbox listener while the list is repopulated programmatically. */
    private var populatingApps = false
    /** The live app list is fetched once per workspace, on demand. EDT only. */
    private var fetchedAppsWorkspace: String? = null
    /** Whether an app fetch is in flight, so the hint can say "loading" rather than "none". EDT only. */
    private var loadingApps = false
    /** The picker's workspace list, fetched on first use and kept until invalidated. EDT only. */
    private var fetchedWorkspaces: List<DesignClient.Workspace>? = null
    /** Whether a workspace fetch is in flight, so the combo can say "loading" rather than "none". EDT only. */
    private var loadingWorkspaces = false

    /**
     * Set by a listener on whatever thread published, honoured later on the EDT. The topic's contract is
     * that a subscriber may only *schedule* work, and the fetched lists are EDT-only state — so the event
     * sets a flag and the next [apply] does the clearing. Without this the Hub kept the previous server's
     * app names after a connection switch.
     */
    @Volatile
    var cachesStale = false
        private set

    /** Both server lists are known to be wrong (environment switched, catalog edited, Refresh pressed). */
    fun invalidate() {
        cachesStale = true
    }

    override fun build(panel: Panel) {
        panel.row(message("hub.design.environment")) {
            environment.placeIn(this)
        }
        manageRow = panel.row {
            link(FlowableActionIds.text(FlowableActionIds.MANAGE_ENVIRONMENTS)) {
                host.invokeAction(FlowableActionIds.MANAGE_ENVIRONMENTS)
            }
        }.visible(false)
        panel.row(message("hub.design.workspace")) {
            cell(workspaceCombo).align(AlignX.FILL).resizableColumn()
        }
        problemRow = panel.row {
            problem.place(this).align(AlignX.FILL).applyToComponent { foreground = NamedColorUtil.getErrorForeground() }
        }.visible(false)
        problemLinksRow = panel.row {
            link(message("hub.design.retry")) { retry() }
            link(FlowableActionIds.text(FlowableActionIds.MANAGE_ENVIRONMENTS)) {
                host.invokeAction(FlowableActionIds.MANAGE_ENVIRONMENTS)
            }
        }.visible(false)
        appsListRow = panel.row { cell(appsScroll).align(AlignX.FILL) }.visible(false)
        appsHintRow = panel.row { appsHint.place(this).align(AlignX.FILL) }
        panel.row {
            // Pulls exactly what is ticked above — the Tools-menu action resolves to the same
            // effective selection, so the two can never disagree. The button names its target, so
            // "which server is this about to hit?" is answered without opening anything; a name too long
            // for the stripe ends in "…", and the tooltip has it whole.
            pullButton = button("") { pullSelected() }.align(AlignX.FILL).component
            HubLayout.narrow(pullButton)
        }
        panel.row { cell(lastPull) }
    }

    override fun apply(s: HubSnapshot) {
        environment.fill(s.designConnections, s.designResolution, s.hasAnyEnvironment)
        manageRow?.visible(!s.hasAnyEnvironment)
        applyPullSelection(s)
        pullButton.text = s.designConnection?.let { message("hub.pull.from", it.environmentName) }
            ?: FlowableActionIds.text(FlowableActionIds.PULL_FROM_DESIGN)
        pullButton.toolTipText = pullButton.text
        pullButton.isEnabled = s.designServerSet
        lastPull.text = if (s.designServerSet) {
            message("hub.design.lastPull", s.lastPullMillis?.let { HubAge.relative(it) } ?: message("hub.design.lastPull.never"))
        } else {
            ""
        }
    }

    /** Renders the workspace and the app list from the effective selection. Seeds key-only placeholders
     *  so the panel shows the selection without a network call; real names arrive with [loadApps] and —
     *  after an environment switch — [loadWorkspaces]. */
    private fun applyPullSelection(s: HubSnapshot) {
        // True exactly on the passes that follow a switch: the one moment both server lists are known to
        // be wrong, and the one moment it is right to go and ask.
        val switched = cachesStale
        if (switched) {
            cachesStale = false
            fetchedWorkspaces = null
            fetchedAppsWorkspace = null
            showProblem(null)
        }
        workspaceCombo.isEnabled = s.designServerSet
        workspacePlaceholder =
            if (s.designServerSet) message("hub.design.workspace.none") else message("hub.design.workspace.noEnvironment")
        if (!s.designServerSet) {
            // "not set" is a state, not a pause: leaving the previous environment's workspace and apps in
            // the controls means the next time they are enabled, they are shown wrong.
            fetchedAppsWorkspace = null
            showProblem(null)
            fillWorkspaces("")
            populateApps(emptyList(), emptySet())
            applyAppsRows("", noEnvironment = true)
            return
        }
        val workspaceKey = s.pullSelection.workspaceKey
        fillWorkspaces(workspaceKey)
        // Picking an environment deserves the real list behind it — so the fetch happens here rather than
        // waiting for the user to discover that the combo has to be opened twice.
        if (switched) loadWorkspaces(force = true, reveal = false)
        val checked = s.pullSelection.appKeys.toSet()
        val known = (0 until appList.model.size).mapNotNull { appList.getItemAt(it) }
        // Keep the fetched apps while the workspace still matches; a switched workspace drops them
        // entirely, since the previous workspace's apps do not exist in the new one.
        val items = if (known.isNotEmpty() && fetchedAppsWorkspace == workspaceKey) known
            else DesignAppListUi.placeholders(checked.sorted())
        populateApps(items, checked)
        if (fetchedAppsWorkspace != workspaceKey) loadApps(force = false)
        applyAppsRows(workspaceKey, noEnvironment = false)
    }

    private fun populateApps(items: List<DesignClient.App>, checked: Set<String>) {
        populatingApps = true
        try {
            DesignAppListUi.populateApps(appList, items, checked)
        } finally {
            populatingApps = false
        }
    }

    /** Shows either the checkbox list or a one-line hint — never an empty list box. */
    private fun applyAppsRows(workspaceKey: String, noEnvironment: Boolean) {
        val hasApps = appList.model.size > 0
        HubLists.sizeToContent(appList, appList.model.size)
        appsScroll.revalidate()
        appsListRow?.visible(hasApps)
        appsHintRow?.visible(!hasApps)
        if (hasApps) return
        appsHint.text = when {
            noEnvironment -> message("hub.design.apps.noEnvironment")
            workspaceKey.isBlank() -> message("hub.design.apps.chooseWorkspace")
            loadingApps -> message("hub.design.apps.loading")
            else -> message("hub.design.apps.none")
        }
    }

    /** Fetches the workspace's apps once (or on demand) so the checkboxes show real names. */
    private fun loadApps(force: Boolean) {
        if (AtlasConnectionSelection.selected(project, ConnectionKind.DESIGN) == null) return
        val workspaceKey = currentSelection().workspaceKey
        if (!force && fetchedAppsWorkspace == workspaceKey) return
        fetchedAppsWorkspace = workspaceKey
        if (workspaceKey.isBlank()) return   // nothing to list, and an empty path segment would only 404
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
                // A failed fetch leaves the list as it was and is not remembered as a fetch, so the next
                // refresh tries again instead of treating an empty list as this workspace's answer.
                if (apps == null) fetchedAppsWorkspace = null
                if (apps != null) {
                    val checked = DesignAppListUi.checkedAppKeys(appList).toSet()
                        .ifEmpty { currentSelection().appKeys.toSet() }
                    populateApps(apps, checked)
                }
                applyAppsRows(workspaceKey, noEnvironment = false)
            }, ModalityState.any())
        }
    }

    /** The connection a list fetch runs with, or null when nothing would authenticate it. Reads the
     *  PasswordSafe, so it must stay off the EDT. */
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

    /** Fills the workspace combo — the stored key as a single placeholder until the list has been fetched. */
    private fun fillWorkspaces(selectedKey: String) {
        val fetched = fetchedWorkspaces
        val items = when {
            fetched != null -> fetched
            selectedKey.isBlank() -> emptyList()
            else -> listOf(DesignClient.Workspace(selectedKey, selectedKey))
        }
        if (loadingWorkspaces && fetched == null) workspacePlaceholder = message("hub.design.workspace.loading")
        populatingCombos = true
        try {
            workspaceCombo.model = DefaultComboBoxModel<DesignClient.Workspace>().apply { items.forEach { addElement(it) } }
            workspaceCombo.selectedItem = items.firstOrNull { it.key == selectedKey }
        } finally {
            populatingCombos = false
        }
        workspaceCombo.toolTipText = items.firstOrNull { it.key == selectedKey }
            ?.let { DesignAppListUi.workspaceLabel(it) } ?: selectedKey.ifBlank { null }
    }

    /**
     * Reads the server's workspace list, once, unless [force]. Never on a plain render — but on the
     * gestures that invalidate the list: opening the picker, switching environment, Refresh.
     *
     * [reveal] is the difference between those: a click on the closed combo has to end with the list open,
     * or the user clicks twice for one list; a fetch nobody asked for must not throw a popup over the panel.
     */
    private fun loadWorkspaces(force: Boolean, reveal: Boolean) {
        if (!force && fetchedWorkspaces != null) return
        val selected = AtlasConnectionSelection.selected(project, ConnectionKind.DESIGN) ?: return
        loadingWorkspaces = true
        if (fetchedWorkspaces == null) fillWorkspaces(currentSelection().workspaceKey)
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val result = designConnection()?.let { DesignClient.listWorkspaces(it) }
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                loadingWorkspaces = false
                workspacePlaceholder = message("hub.design.workspace.none")
                when {
                    // An unreadable keychain entry and a server error need different fixes.
                    result == null -> showProblem(message("hub.design.problem.noCredentials", selected.baseUrl))
                    result is DesignClient.Result.Failed -> showProblem(result.message)
                    result is DesignClient.Result.Success && result.value.isEmpty() ->
                        showProblem(message("hub.design.problem.noWorkspaces"))
                    result is DesignClient.Result.Success -> {
                        showProblem(null)
                        fetchedWorkspaces = result.value
                        if (reveal && !workspaceCombo.isPopupVisible) {
                            fillWorkspaces(currentSelection().workspaceKey)
                            workspaceCombo.showPopup()
                        }
                    }
                }
                fillWorkspaces(currentSelection().workspaceKey)
            }, ModalityState.any())
        }
    }

    /** Store the picked workspace. Apps do not carry over — the next workspace does not have them. */
    fun selectWorkspace(workspaceKey: String) {
        val current = currentSelection()
        if (workspaceKey == current.workspaceKey) return
        store(DesignPullSelection.withWorkspace(current, workspaceKey))
    }

    /** A checkbox was toggled — that *is* the project's selection now, not a copy of it. */
    private fun onSelectionEdited() {
        store(DesignPullSelection(currentSelection().workspaceKey, DesignAppListUi.checkedAppKeys(appList)))
    }

    /** Writes the selection into the project settings, under the selected environment's name. */
    private fun store(selection: DesignPullSelection) {
        val connection = AtlasConnectionSelection.selected(project, ConnectionKind.DESIGN) ?: return
        FlowableAtlasProjectSettings.getInstance(project).pullTarget(connection.environmentName).also {
            it.workspaceKey = selection.workspaceKey
            it.appKeys = selection.appKeys.toMutableList()
        }
        host.requestRefresh()
    }

    /** Why the lists could not be read, under the picker that stayed empty — or nothing, with [text] null. */
    private fun showProblem(text: String?) {
        problem.text = text.orEmpty()
        problemRow?.visible(text != null)
        problemLinksRow?.visible(text != null)
    }

    /** The problem row's *Retry*: both lists again, as if the environment had just been picked. */
    private fun retry() {
        showProblem(null)
        loadWorkspaces(force = true, reveal = false)
        loadApps(force = true)
    }

    private fun pullSelected() {
        if (AtlasConnectionSelection.selected(project, ConnectionKind.DESIGN) == null) return  // button is disabled then
        val selection = currentSelection()
        project.service<DesignPullService>().pullInBackground(selection.workspaceKey, selection.appKeys)
    }

    private fun currentSelection(): DesignPullSelection =
        AtlasConnectionSelection.selected(project, ConnectionKind.DESIGN)
            ?.let { AtlasDesignTarget.selection(project, it) }
            ?: DesignPullSelection.EMPTY

    // -- for tests -------------------------------------------------------------------------------

    val pullText: String get() = pullButton.text
    val problemText: String? get() = problem.text.ifEmpty { null }
    val workspaceKey: String? get() = (workspaceCombo.selectedItem as? DesignClient.Workspace)?.key
    val appKeys: List<String> get() = (0 until appList.model.size).mapNotNull { appList.getItemAt(it)?.key }
    val appRows: Int get() = appList.visibleRowCount
}
