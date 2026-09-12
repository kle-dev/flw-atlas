package com.flowable.atlas.hub

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
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.SingleAlarm
import java.nio.file.Path
import javax.swing.JComponent

/**
 * Content of the Atlas Hub tool window: a status header (which Flowable project, how many models, how
 * fresh, and one attention line when something needs a hand) over three task blocks — the generated
 * explorer, the Flowable Design pull, the playground's runtime — each with its actions beside its state.
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

    private val refreshAlarm = SingleAlarm(::refreshNow, 300, this)
    private var last: HubSnapshot? = null

    override val anchor: JComponent get() = this

    init {
        toolbar = ActionManager.getInstance()
            .createActionToolbar("AtlasHub", HubActions.toolbar(project, ::refreshEverything), true)
            .also { it.targetComponent = this }
            .component

        setContent(JBScrollPane(panel {
            header.build(this)
            separator()
            sections.forEach { it.build(this) }
            // The running version, in plain sight: the one line of the old footer that people read.
            separator()
            row { comment("Flowable Atlas ${HubHeader.atlasVersion()}") }
        }))

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
            is HubAttention.UnreadableArchives -> JBPopupFactory.getInstance()
                .createPopupChooserBuilder(attention.names)
                .setTitle(message("hub.attention.archives.title"))
                .createPopup()
                .showUnderneathOf(header.projectCombo)
            HubAttention.StaleExplorer -> AtlasGenerationRunner.regenerate(project)
        }
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
        attention = header.attentionText,
        hasEnvironments = last?.hasAnyEnvironment ?: false,
    )
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
)
