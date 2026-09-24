package com.flowable.atlas.findings

import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.explorer.AtlasFileLabels
import com.flowable.atlas.findings.FindingsTree.CheckItem
import com.flowable.atlas.findings.FindingsTree.FindingItem
import com.flowable.atlas.findings.FindingsTree.Group
import com.flowable.atlas.hub.HubAge
import com.flowable.atlas.hub.HubLayout
import com.flowable.atlas.hub.HubText
import com.flowable.atlas.icons.AtlasIcons
import com.flowable.atlas.intention.OpenInAtlasExplorerIntention
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SideBorder
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * The *Atlas Findings* tool window: every finding of the last analysis, grouped as defects, advice and
 * accepted, then by check — the explorer's Checks page in Swing, so it works under Remote Development and
 * without JCEF.
 *
 * Three parts, the way the platform's own Problems view is laid out: a vertical toolbar, a status line
 * saying how current the analysis is (*3 defects · 41 advice · analyzed 2 min ago*, and when a model
 * changed since, how many and *Analyze Again*), and the tree beside a detail pane. The pane is what the
 * page says about a check — why it matters, what to do, where to read more — and the way into the
 * explorer at the finding's model. A double-click or Enter opens the finding's file at its line;
 * *Accept…* writes a rule to the same `waivers.json` the explorer's Save writes.
 */
internal class AtlasFindingsPanel(private val project: Project) : SimpleToolWindowPanel(false, true), Disposable {

    private val service = AtlasFindingsService.getInstance(project)
    private val props = PropertiesComponent.getInstance(project)
    private var showAdvice = props.getBoolean(SHOW_ADVICE, false)
    private var showAccepted = props.getBoolean(SHOW_ACCEPTED, false)

    internal val tree = Tree(DefaultTreeModel(DefaultMutableTreeNode())).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = Renderer()
        emptyText.text = message("findings.empty.none")
        emptyText.appendSecondaryText(message("findings.analyze"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { service.refresh() }
    }

    private val statusIcon = JBLabel()
    private val statusText = JBLabel()
    private val statusLink = ActionLink(message("findings.analyzeAgain")) { service.refresh() }
    private val detailHolder = JPanel(BorderLayout())

    /** What the pane shows now — read by the toolbar's *Open in Atlas Explorer*. */
    internal var detail: FindingsDetail = FindingsDetail.EMPTY
        private set

    init {
        val expander = DefaultTreeExpander(tree)
        val actions = DefaultActionGroup(
            refreshAction(), acceptAction(), Separator.getInstance(),
            CommonActionsManager.getInstance().createExpandAllAction(expander, tree),
            CommonActionsManager.getInstance().createCollapseAllAction(expander, tree),
            Separator.getInstance(),
            showGroup(), openInExplorerAction(),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("AtlasFindings", actions, false)
        toolbar.targetComponent = tree
        setToolbar(toolbar.component)

        val status = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(3))).apply {
            add(statusIcon)
            add(statusText)
            add(statusLink)
            border = SideBorder(UIUtil.getBoundsColor(), SideBorder.BOTTOM)
        }
        val splitter = OnePixelSplitter(false, SPLIT_KEY, 0.6f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(tree, true)
            secondComponent = detailHolder
        }
        setContent(JPanel(BorderLayout()).apply {
            add(status, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        })

        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
        EditSourceOnDoubleClickHandler.install(tree) { open() }
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ENTER) { open(); e.consume() } }
        })
        tree.addTreeSelectionListener { showDetail() }
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(acceptAction(), openInExplorerAction()), "AtlasFindingsPopup")
        service.addListener(this, ::reload)
        reload()
        if (service.last == null) service.refresh()
    }

    private fun reload() {
        val analysis = service.last
        tree.emptyText.text = when {
            service.running -> message("findings.empty.running")
            analysis == null -> message("findings.empty.none")
            else -> message("findings.empty.clean")
        }
        val selected = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject
        val root = FindingsTree.build(analysis?.findings.orEmpty(), showAdvice, showAccepted)
        (tree.model as DefaultTreeModel).setRoot(root)
        // The groups and checks open, the findings under them listed: the tree is read, not explored.
        TreeUtil.expand(tree, 2)
        selected?.let { keep ->
            TreeUtil.findNode(root) { it.userObject == keep }?.let { TreeUtil.selectNode(tree, it) }
        }
        applyStatus(analysis)
        showDetail()
    }

    private fun applyStatus(analysis: AtlasFindingsService.Analysis?) {
        statusLink.isVisible = false
        when {
            service.running -> {
                statusIcon.icon = AnimatedIcon.Default.INSTANCE
                statusText.text = message("findings.status.running")
            }
            analysis == null -> {
                statusIcon.icon = AllIcons.General.Information
                statusText.text = message("findings.status.none")
                statusLink.text = message("findings.analyze")
                statusLink.isVisible = true
            }
            else -> {
                statusIcon.icon = if (analysis.defects > 0) AllIcons.General.Error else AllIcons.General.InspectionsOK
                val changed = if (service.stale) service.changedSinceAnalysis() else emptyList()
                statusText.text = buildString {
                    append(message("findings.status.counts", analysis.defects, analysis.advice))
                    append(" · ").append(message("findings.status.analyzed", HubAge.relative(analysis.atMillis)))
                    if (service.stale) {
                        append(" · ")
                        append(if (changed.isEmpty()) message("findings.status.staleUnnamed") else message("findings.status.stale", changed.size))
                    }
                }
                statusText.toolTipText = changed.takeIf { it.isNotEmpty() }?.joinToString(", ")
                if (service.stale) {
                    statusLink.text = message("findings.analyzeAgain")
                    statusLink.isVisible = true
                }
            }
        }
    }

    private fun selectedItem(): FindingsTree.Item? =
        (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? FindingsTree.Item

    private fun showDetail() {
        detail = FindingsDetail.of(selectedItem(), selectedFindings().size)
        val d = detail
        // Paragraphs wrap to the pane's width whatever it is — HubText, not the DSL's word-wrapping text,
        // which asks for its whole line and pushed the pane wider than the splitter gave it.
        fun Row.para(text: String, grey: Boolean = false) = HubText(comment = grey).also { it.text = text }.place(this).align(AlignX.FILL)
        val content = panel {
            row { label(d.title).bold() }
            d.kind?.let { kind -> row { para(kind, grey = true) } }
            d.finding?.let { text -> row { para(text) } }
            d.file?.let { file ->
                row(message("findings.detail.where")) {
                    link(file.substringAfterLast('/') + (d.line?.let { ":$it" } ?: "")) { open() }
                        .applyToComponent { toolTipText = file }
                }
            }
            d.accepted?.let { text -> row(message("findings.detail.accepted")) { para(text) } }
            d.what?.let { text -> row { para(text) } }
            d.why?.let { text ->
                row { label(message("findings.detail.why")).bold() }.topGap(TopGap.SMALL)
                row { para(text) }
            }
            d.fix?.let { text ->
                row { label(message("findings.detail.fix")).bold() }.topGap(TopGap.SMALL)
                row { para(text) }
            }
            row {
                d.route?.let { route -> link(message("findings.openInExplorer")) { OpenInAtlasExplorerIntention.openRoute(project, route) }.gap(RightGap.SMALL) }
                d.docsUrl?.let { url -> browserLink(message("findings.detail.docs"), url).gap(RightGap.SMALL) }
                if (d.acceptable > 0) link(message("findings.accept")) { accept() }
            }.topGap(TopGap.SMALL)
        }.withBorder(JBUI.Borders.empty(8, 12))
        detailHolder.removeAll()
        detailHolder.add(HubLayout.scroll(content), BorderLayout.CENTER)
        detailHolder.revalidate()
        detailHolder.repaint()
    }

    private fun selectedFindings(): List<Map<String, Any?>> =
        tree.selectionPaths.orEmpty().flatMap { path ->
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return@flatMap emptyList()
            when (node.userObject) {
                is FindingItem -> listOf((node.userObject as FindingItem).finding)
                // a check or group selected stands for every finding under it
                else -> node.depthFirstEnumeration().toList().mapNotNull { ((it as DefaultMutableTreeNode).userObject as? FindingItem)?.finding }
            }
        }.filter { it["waived"] == null }.distinct()

    /** Opens the finding's file at its line. The file is resolved off the EDT — it may need a refresh. */
    private fun open() {
        val item = selectedItem() as? FindingItem ?: return
        val root = service.last?.root ?: return
        val label = item.file ?: return
        val line = item.line
        ApplicationManager.getApplication().executeOnPooledThread {
            val vf = AtlasFileLabels.resolve(root, label) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                (if (line != null && line > 0) OpenFileDescriptor(project, vf, line - 1, 0) else OpenFileDescriptor(project, vf)).navigate(true)
            }, ModalityState.any())
        }
    }

    private fun accept() {
        val findings = selectedFindings().takeIf { it.isNotEmpty() } ?: return
        val dialog = AcceptFindingsDialog(project, findings.size)
        if (dialog.showAndGet()) service.accept(findings, dialog.reason)
    }

    private fun refreshAction() = object : DumbAwareAction(
        message("findings.analyzeAgain"), message("findings.analyzeAgain.description"), AllIcons.Actions.Refresh,
    ) {
        override fun actionPerformed(e: AnActionEvent) = service.refresh()
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = !service.running }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private fun acceptAction() = object : DumbAwareAction(
        message("findings.accept"), message("findings.accept.description"), AllIcons.Actions.Checked,
    ) {
        override fun actionPerformed(e: AnActionEvent) = accept()
        // The pane already counted what Accept would take — no second walk of the selected subtree on
        // every toolbar update.
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = service.last != null && !service.running && detail.acceptable > 0 }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private fun openInExplorerAction() = object : DumbAwareAction(
        message("findings.openInExplorer"), message("findings.openInExplorer.description"), AtlasIcons.Explorer,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            detail.route?.let { OpenInAtlasExplorerIntention.openRoute(project, it) }
        }
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = detail.route != null }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    /** The two filters behind one eye, the way the Problems view keeps its own — a vertical toolbar has no
     *  room for two text buttons, which is what they rendered as without an icon of their own. */
    private fun showGroup() = DefaultActionGroup(message("findings.show"), true).apply {
        templatePresentation.icon = AllIcons.Actions.Show
        add(toggle(message("findings.show.advice"), SHOW_ADVICE, { showAdvice }) { showAdvice = it })
        add(toggle(message("findings.show.accepted"), SHOW_ACCEPTED, { showAccepted }) { showAccepted = it })
    }

    private fun toggle(text: String, key: String, get: () -> Boolean, set: (Boolean) -> Unit) = object : DumbAwareToggleAction(text) {
        override fun isSelected(e: AnActionEvent): Boolean = get()
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            set(state)
            props.setValue(key, state, false)
            reload()
        }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is Group -> {
                    append(item.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${item.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is CheckItem -> {
                    append(item.title)
                    append("  ${item.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is FindingItem -> {
                    icon = if (item.finding["waived"] != null) AllIcons.General.InspectionsOK
                        else if (item.isAdvice) AllIcons.General.Information
                        else if (item.isError) AllIcons.General.Error else AllIcons.General.Warning
                    append(item.label)
                    if (item.message.isNotEmpty()) append(" — ${item.message}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    item.file?.let { append("  ${it.substringAfterLast('/')}${item.line?.let { l -> ":$l" } ?: ""}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                }
            }
        }
    }

    override fun dispose() {}

    // -- for tests -------------------------------------------------------------------------------

    internal val statusForTest: String get() = statusText.text
    internal val statusLinkForTest: String? get() = statusLink.text.takeIf { statusLink.isVisible }

    private companion object {
        const val SHOW_ADVICE = "flowable.atlas.findings.showAdvice"
        const val SHOW_ACCEPTED = "flowable.atlas.findings.showAccepted"
        const val SPLIT_KEY = "flowable.atlas.findings.split"
    }
}
