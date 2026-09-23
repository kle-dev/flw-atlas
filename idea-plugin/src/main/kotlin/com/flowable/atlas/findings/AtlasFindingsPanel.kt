package com.flowable.atlas.findings

import com.flowable.atlas.explorer.AtlasFileLabels
import com.flowable.atlas.findings.FindingsTree.CheckItem
import com.flowable.atlas.findings.FindingsTree.FindingItem
import com.flowable.atlas.findings.FindingsTree.Group
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.ui.tree.TreeUtil
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * The *Atlas Findings* tool window: every finding of the last analysis, grouped as defects, advice and
 * accepted, then by check — the explorer's Checks page in Swing, so it works under Remote Development and
 * without JCEF. A double-click or Enter opens the finding's file at its line; *Accept…* writes a rule to
 * the same `waivers.json` the explorer's Save writes.
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
        emptyText.text = "No analysis yet"
        emptyText.appendSecondaryText("Analyze the project", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { service.refresh() }
    }

    init {
        val actions = DefaultActionGroup(refreshAction(), acceptAction(), toggle("Show Advice", SHOW_ADVICE, { showAdvice }) { showAdvice = it },
            toggle("Show Accepted", SHOW_ACCEPTED, { showAccepted }) { showAccepted = it })
        val toolbar = ActionManager.getInstance().createActionToolbar("AtlasFindings", actions, false)
        toolbar.targetComponent = tree
        setToolbar(toolbar.component)
        setContent(ScrollPaneFactory.createScrollPane(tree))
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
        EditSourceOnDoubleClickHandler.install(tree) { open() }
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ENTER) { open(); e.consume() } }
        })
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(acceptAction()), "AtlasFindingsPopup")
        service.addListener(this, ::reload)
        reload()
        if (service.last == null) service.refresh()
    }

    private fun reload() {
        val analysis = service.last
        tree.emptyText.text = when {
            service.running -> "Analyzing…"
            analysis == null -> "No analysis yet"
            else -> "No findings"
        }
        val root = FindingsTree.build(analysis?.findings.orEmpty(), showAdvice, showAccepted)
        (tree.model as DefaultTreeModel).setRoot(root)
        // The groups and checks open, the findings under them listed: the tree is read, not explored.
        TreeUtil.expand(tree, 2)
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

    private fun open() {
        val item = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? FindingItem ?: return
        val root = service.last?.root ?: return
        val label = item.file ?: return
        val vf = AtlasFileLabels.resolve(root, label) ?: return
        val line = item.line
        (if (line != null && line > 0) OpenFileDescriptor(project, vf, line - 1, 0) else OpenFileDescriptor(project, vf)).navigate(true)
    }

    private fun refreshAction() = object : DumbAwareAction("Analyze Again", "Run the analysis again and refresh the findings", AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) = service.refresh()
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = !service.running }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private fun acceptAction() = object : DumbAwareAction("Accept…", "Accept the selected findings in waivers.json, with a reason", AllIcons.Actions.Checked) {
        override fun actionPerformed(e: AnActionEvent) {
            val findings = selectedFindings().takeIf { it.isNotEmpty() } ?: return
            val reason = Messages.showInputDialog(
                project,
                "Why is ${if (findings.size == 1) "this finding" else "each of these ${findings.size} findings"} acceptable? " +
                    "The reason is stored with the rule in waivers.json.",
                "Accept Findings", null,
            )?.trim()?.takeIf { it.isNotEmpty() } ?: return
            service.accept(findings, reason)
        }
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = service.last != null && !service.running && selectedFindings().isNotEmpty() }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
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
                    icon = if (item.finding["waived"] != null) AllIcons.RunConfigurations.TestIgnored
                        else if (item.isError) AllIcons.General.Error else AllIcons.General.Warning
                    append(item.label)
                    if (item.message.isNotEmpty()) append(" — ${item.message}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    item.file?.let { append("  ${it.substringAfterLast('/')}${item.line?.let { l -> ":$l" } ?: ""}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                }
            }
        }
    }

    override fun dispose() {}

    private companion object {
        const val SHOW_ADVICE = "flowable.atlas.findings.showAdvice"
        const val SHOW_ACCEPTED = "flowable.atlas.findings.showAccepted"
    }
}
