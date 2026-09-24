package com.flowable.atlas.hub

import com.intellij.openapi.components.service
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.AtlasNotifications
import com.intellij.notification.NotificationType
import com.intellij.ide.projectView.ProjectView
import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.action.FlowableActionIds
import com.flowable.atlas.environment.ConnectionKind
import com.flowable.atlas.events.AtlasEvents
import com.flowable.atlas.events.AtlasEventsListener
import com.flowable.atlas.explorer.AtlasGenerationRunner
import com.flowable.atlas.hub.sections.DesignPullSection
import com.flowable.atlas.hub.sections.ExplorerSection
import com.flowable.atlas.hub.sections.HubHost
import com.flowable.atlas.hub.sections.PlaygroundSection
import com.flowable.atlas.hub.sections.RecentModelsSection
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RowsRange
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.util.ui.JBUI
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.SingleAlarm
import java.nio.file.Path
import javax.swing.JComponent

/**
 * Content of the Atlas Hub tool window: a status header (which Flowable project, how many models, how
 * fresh, and one attention line when something needs a hand) over four foldable blocks — the generated
 * explorer, the models opened last, the Flowable Design pull, the playground's runtime — each with its
 * actions beside its state. The panel is as wide as its stripe and every row fits 280 px ([HubLayout]);
 * a folded block stays folded, per project.
 *
 * Data is gathered on a pooled thread ([HubSnapshot.gather]) and applied on the EDT; the panel never
 * triggers a blocking index build itself. It refreshes via [AtlasEvents] with a debounce, so it reflects
 * work started anywhere in the IDE without polling.
 */
class AtlasHubPanel(override val project: Project) : SimpleToolWindowPanel(true, true), Disposable, HubHost {

    private val header = HubHeader(this, ::runAttention)
    private val explorer = ExplorerSection(this)
    private val recent = RecentModelsSection(this)
    private val design = DesignPullSection(this)
    private val playground = PlaygroundSection(this)
    private val sections = listOf(explorer, recent, design, playground)

    private val folds = LinkedHashMap<String, FoldHeader>()
    private val refreshAlarm = SingleAlarm(::refreshNow, 300, this)
    private var last: HubSnapshot? = null

    override val anchor: JComponent get() = this

    init {
        toolbar = ActionManager.getInstance()
            .createActionToolbar("AtlasHub", HubActions.toolbar(project, ::refreshEverything), true)
            .also { it.targetComponent = this }
            .component

        setContent(HubLayout.scroll(panel {
            header.build(this)
            sections.forEach { section ->
                // No indent: in a 280 px stripe the fold's chevron is all the hierarchy a block needs.
                lateinit var rows: RowsRange
                val header = FoldHeader(section.title, HubLayout.expanded(project, section.id)) { open ->
                    rows.visible(open)
                    HubLayout.rememberExpanded(project, section.id, open)
                }
                row { cell(header).align(AlignX.FILL) }.topGap(TopGap.MEDIUM)
                rows = rowsRange { section.build(this) }
                rows.visible(header.expanded)
                folds[section.id] = header
            }
            // The running version, in plain sight: the one line of the old footer that people read.
            separator()
            row { comment(message("hub.footer.version", HubHeader.atlasVersion())) }
        }.withBorder(JBUI.Borders.empty(4, 10, 8, 10))))

        project.messageBus.connect(this).subscribe(AtlasEvents.TOPIC, object : AtlasEventsListener {
            override fun modelIndexUpdated() = refreshAlarm.cancelAndRequest()
            override fun artifactsGenerated(explorerHtml: Path?, written: List<Path>) = refreshAlarm.cancelAndRequest()
            override fun designPullFinished(succeeded: Boolean) = refreshAlarm.cancelAndRequest()
            override fun activeSubProjectChanged() = refreshAlarm.cancelAndRequest()
            override fun recentModelsChanged() = refreshAlarm.cancelAndRequest()
            override fun settingsApplied() = refreshAlarm.cancelAndRequest()
            override fun environmentsChanged() = refreshEverything()
            override fun connectionSelectionChanged(kind: ConnectionKind) = refreshEverything()
        })
        refreshAlarm.request()
    }

    /** A re-gather that also re-reads the Flowable Design lists — the toolbar's Refresh, and every event
     *  after which those lists are known to be wrong. */
    private fun refreshEverything() {
        design.invalidate()
        // A page the command line wrote into a folder the IDE never refreshed sends no file event.
        com.flowable.atlas.explorer.AtlasExplorerFiles.forget()
        refreshAlarm.cancelAndRequest()
    }

    override fun requestRefresh() = refreshAlarm.cancelAndRequest()

    override fun invokeAction(id: String) {
        val action = ActionManager.getInstance().getAction(id) ?: return
        val context = DataManager.getInstance().getDataContext(this)
        val event = AnActionEvent.createEvent(action, context, null, "AtlasHub", ActionUiKind.NONE, null)
        ActionUtil.performAction(action, event)
    }

    /** Fired by the (EDT) alarm; gathers on a pooled thread, applies on the EDT. */
    private fun refreshNow() {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val snapshot = HubSnapshot.gather(project) { refreshAlarm.cancelAndRequest() }
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) apply(snapshot)
            }, ModalityState.any())
        }
    }

    private fun apply(snapshot: HubSnapshot) {
        last = snapshot
        header.apply(snapshot, HubAttention.of(snapshot))
        sections.forEach { it.apply(snapshot) }
    }

    /** The attention line's one action. */
    private fun runAttention(attention: HubAttention) {
        when (attention) {
            is HubAttention.RemovedEnvironment -> invokeAction(FlowableActionIds.MANAGE_ENVIRONMENTS)
            is HubAttention.ChooseProject -> header.projectCombo.showPopup()
            is HubAttention.IndexFailed -> invokeAction(FlowableActionIds.REBUILD_MODEL_INDEX)
            // A pick shows the archive in the Project view and says why it could not be read.
            is HubAttention.UnreadableArchives -> JBPopupFactory.getInstance()
                .createPopupChooserBuilder(attention.names)
                .setTitle(message("hub.attention.archives.title"))
                .setItemChosenCallback(::showUnreadableArchive)
                .createPopup()
                .showUnderneathOf(header.projectCombo)
            is HubAttention.StaleExplorer -> AtlasGenerationRunner.regenerate(project)
        }
    }

    private fun showUnreadableArchive(name: String) {
        val skipped = project.service<FlowableModelIndexService>().cachedOrNull()?.skippedArchiveFiles?.get(name) ?: return
        if (skipped.file.isValid) ProjectView.getInstance(project).select(null, skipped.file, true)
        AtlasNotifications.group()
            .createNotification(message("hub.attention.archives.why", name, skipped.reason), NotificationType.WARNING)
            .notify(project)
    }

    override fun dispose() {}

    // -- for tests -------------------------------------------------------------------------------

    /** Gather + apply synchronously — for tests, which cannot wait on the pooled refresh. */
    internal fun refreshForTest() = apply(HubSnapshot.gather(project) {})

    /** The refresh runs behind a 300 ms alarm no test can fast-forward, so tests assert the flag the
     *  listener sets synchronously instead of a repaint they would have to wait for. */
    internal val designCachesStaleForTest: Boolean get() = design.cachesStale

    /** Pick a workspace the way the popup's callback does — for tests, which have no popup. */
    internal fun selectWorkspaceForTest(workspaceKey: String) = design.selectWorkspace(workspaceKey)

    /** Pick the combo's "not set" entry the way a click does — for tests, which have no combo. */
    internal fun chooseNoEnvironmentForTest(kind: ConnectionKind) =
        (if (kind == ConnectionKind.DESIGN) design.environment else playground.environment).choose(null)

    /** Pick a Flowable project the way the combo does — for tests, which have no combo. */
    internal fun chooseProjectForTest(relPath: String?) = header.chooseSubProject(relPath)

    /** What the panel currently shows, as a reader would read it. */
    internal fun viewForTest(): HubView = HubView(
        projectItems = header.projectItems,
        connectionLines = mapOf(
            ConnectionKind.DESIGN to design.environment.line(),
            ConnectionKind.WORK to playground.environment.line(),
        ),
        pullText = design.pullText,
        workspaceKey = design.workspaceKey,
        appKeys = design.appKeys,
        explorerHint = explorer.hintText,
        statusText = header.statusText,
        recentKeys = recent.keys,
        listRows = explorer.rows to design.appRows,
        attention = header.attention,
        hasEnvironments = last?.hasAnyEnvironment ?: false,
        foldedSections = folds.filterValues { !it.expanded }.keys.toList(),
        designProblem = design.problemText,
    )

    /** Fold or unfold a block the way a click on its title does — for tests, which have no mouse. */
    internal fun foldForTest(id: String, expanded: Boolean) {
        folds.getValue(id).expanded = expanded
    }
}

/** The rendered Hub, for tests: strings and sizes, never components. */
internal data class HubView(
    val projectItems: List<String>,
    val connectionLines: Map<ConnectionKind, String>,
    val pullText: String,
    val workspaceKey: String?,
    val appKeys: List<String>,
    val explorerHint: String,
    /** `142 models` — the link's text; `scanning…` / `index failed` before an index exists. */
    val statusText: String,
    val recentKeys: List<String>,
    /** Rows the explorer list and the app list reserve — the panel's height budget in two numbers. */
    val listRows: Pair<Int, Int>,
    val attention: String?,
    val hasEnvironments: Boolean,
    /** Ids of the blocks folded shut. */
    val foldedSections: List<String>,
    /** Why the Design lists could not be read, when they could not. */
    val designProblem: String?,
)
