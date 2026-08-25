package com.flowable.atlas.settings.connections

import com.flowable.atlas.environment.AtlasConnectionSelection
import com.flowable.atlas.environment.AtlasEnvironments
import com.flowable.atlas.environment.ConnectionKind
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.CardLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The environment editor: a tree of environments and their connections on the left, the selected
 * node's form on the right.
 *
 * A plain [Tree] in a [ToolbarDecorator] rather than the platform's `MasterDetailsComponent`, which
 * would have supplied the tree and toolbar for free. Three reasons it was the wrong trade here. It
 * wants to *be* the whole page, leaving no seam for the project's own settings below the splitter.
 * Its `reset()` rebuilds every node and resets every child configurable, which with eight connections
 * would mean eight PasswordSafe round-trips on every page open — this design reads credentials only
 * for the node you select. And every detail form would have to become a `NamedConfigurable`, a raw
 * Swing API with no Kotlin UI DSL story, so the forms would stop looking like every other Atlas page.
 *
 * The page edits a [ConnectionsDraft] and only touches the catalog in [apply], which is what makes
 * *Cancel* mean something after a removal or a rename.
 */
class EnvironmentsTreePanel(private val project: Project) : Disposable {

    /** What a tree node stands for; the ids are draft ids, resolved on demand. */
    private sealed interface Node {
        data class Env(val id: String) : Node
        data class Conn(val id: String) : Node
    }

    private val catalog = AtlasEnvironments.getInstance()

    private var draft = ConnectionsDraft.from(catalog)

    /** The state the draft is compared against for `isModified()`. */
    private var baseline = draft.snapshot()

    private val root = DefaultMutableTreeNode("Environments")
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        emptyText.text = "No environments yet"
        emptyText.appendLine("Add one", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { addEnvironment() }
        cellRenderer = NodeRenderer()
    }

    private val designForm = DesignConnectionForm(project)
    private val workForm = WorkConnectionForm(project)
    private val environmentForm = EnvironmentForm(
        onEditConnection = { kind -> selectedEnvironmentId()?.let { env -> selectConnection(env, kind) } },
        onAddConnection = { kind -> selectedEnvironmentId()?.let { env -> addConnection(env, kind) } },
    )

    private val cards = CardLayout()
    private val detail = JPanel(cards).apply {
        add(JPanel(), CARD_EMPTY)
        add(environmentForm.component, CARD_ENVIRONMENT)
        add(designForm.component, CARD_DESIGN)
        add(workForm.component, CARD_WORK)
    }

    /** Which form currently holds unflushed edits, so a node switch never loses typing. */
    private var loaded: Node? = null

    val component: JComponent = JBSplitter(false, "flowable.atlas.environments.splitter", 0.34f).apply {
        firstComponent = ToolbarDecorator.createDecorator(tree)
            .setAddAction { button -> showAddPopup(button) }
            .setRemoveAction { removeSelected() }
            .setMoveUpAction { moveSelected(-1) }
            .setMoveDownAction { moveSelected(1) }
            .addExtraAction(copyAction())
            .createPanel()
        secondComponent = JBScrollPane(detail).apply { border = JBUI.Borders.empty() }
    }

    init {
        Disposer.register(this, designForm)
        Disposer.register(this, workForm)
        tree.addTreeSelectionListener {
            flushCurrentForm()
            repaintLabels()      // a rename made in the form before this switch
            showSelected()
        }
        rebuildTree(select = null)
    }

    // ---- the Configurable's contract ----------------------------------------------------------

    fun reset() {
        draft = ConnectionsDraft.from(catalog)
        baseline = draft.snapshot()
        loaded = null
        rebuildTree(select = null)
    }

    fun isModified(): Boolean {
        flushCurrentForm()
        return draft.snapshot() != baseline || designForm.secretsModified() || workForm.secretsModified()
    }

    /** The first validation problem, or null — the page turns this into a `ConfigurationException`. */
    fun validate(): String? {
        flushCurrentForm()
        return draft.validate()
    }

    fun apply() {
        flushCurrentForm()
        catalog.replaceAll(draft.toEnvironmentStates(), draft.toConnectionStates())
        designForm.saveSecrets()
        workForm.saveSecrets()
        baseline = draft.snapshot()
        rebuildTree(select = loaded)
    }

    override fun dispose() {}

    /** Opens the page on a specific node — what every "Configure…" deep link needs to avoid a dead end. */
    fun select(node: SelectionTarget) {
        when (node) {
            is SelectionTarget.Connection -> selectNode(Node.Conn(node.id))
            is SelectionTarget.Environment -> selectNode(Node.Env(node.id))
            SelectionTarget.FirstOfKind -> draft.connections.firstOrNull()?.let { selectNode(Node.Conn(it.id)) }
            SelectionTarget.Nothing -> Unit
        }
    }

    /** Where a deep link should land. */
    sealed interface SelectionTarget {
        data class Connection(val id: String) : SelectionTarget
        data class Environment(val id: String) : SelectionTarget
        data object FirstOfKind : SelectionTarget
        data object Nothing : SelectionTarget
    }

    // ---- tree plumbing ------------------------------------------------------------------------

    private fun rebuildTree(select: Node?) {
        root.removeAllChildren()
        draft.environments.forEach { env ->
            val envNode = DefaultMutableTreeNode(Node.Env(env.id))
            draft.connectionsOf(env.id).forEach { envNode.add(DefaultMutableTreeNode(Node.Conn(it.id))) }
            root.add(envNode)
        }
        treeModel.reload()
        expandAll()
        if (select != null) selectNode(select) else showSelected()
    }

    private fun expandAll() {
        for (i in 0 until tree.rowCount) tree.expandRow(i)
    }

    private fun nodes(): List<DefaultMutableTreeNode> {
        val out = mutableListOf<DefaultMutableTreeNode>()
        fun walk(parent: DefaultMutableTreeNode) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i) as DefaultMutableTreeNode
                out += child
                walk(child)
            }
        }
        walk(root)
        return out
    }

    private fun selectNode(node: Node) {
        val match = nodes().firstOrNull { it.userObject == node } ?: return
        val path = TreePath(match.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun selectedNode(): Node? =
        (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Node

    private fun selectedEnvironmentId(): String? = when (val node = selectedNode()) {
        is Node.Env -> node.id
        is Node.Conn -> draft.connection(node.id)?.environmentId
        null -> null
    }

    private fun selectConnection(environmentId: String, kind: ConnectionKind) {
        draft.connectionsOf(environmentId).firstOrNull { it.kind == kind }
            ?.let { selectNode(Node.Conn(it.id)) }
    }

    /**
     * Writes the visible form back into the draft — before every switch, and before Apply.
     *
     * Deliberately touches **no UI**. It is reached from [isModified], which the Settings dialog polls;
     * repainting a tree node from there feeds the poll loop and the page never finishes loading. The
     * labels are refreshed explicitly by [repaintLabels] when something actually changed.
     */
    private fun flushCurrentForm() {
        when (loaded) {
            is Node.Env -> environmentForm.flush()
            is Node.Conn -> {
                designForm.flush()
                workForm.flush()
            }
            null -> Unit
        }
    }

    /** Redraws the node labels — a rename or a changed URL is visible in the tree. */
    private fun repaintLabels() {
        nodes().forEach { treeModel.nodeChanged(it) }
    }

    private fun showSelected() {
        when (val node = selectedNode()) {
            is Node.Env -> {
                val env = draft.environment(node.id) ?: return showEmpty()
                environmentForm.load(env, draft)
                cards.show(detail, CARD_ENVIRONMENT)
                loaded = node
            }
            is Node.Conn -> {
                val conn = draft.connection(node.id) ?: return showEmpty()
                if (conn.kind == ConnectionKind.DESIGN) {
                    designForm.load(conn)
                    cards.show(detail, CARD_DESIGN)
                } else {
                    workForm.load(conn)
                    cards.show(detail, CARD_WORK)
                }
                loaded = node
            }
            null -> showEmpty()
        }
    }

    private fun showEmpty() {
        cards.show(detail, CARD_EMPTY)
        loaded = null
    }

    // ---- toolbar actions ----------------------------------------------------------------------

    /**
     * [button] is the toolbar's own `+`, and the popup is anchored to it via `preferredPopupPoint`.
     * Anchoring it to the tree instead put it in the bottom-left corner of the screen whenever the
     * tree was empty — which is exactly when a first-time user needs it — and a popup stranded there
     * still holds the settings dialog, so every other page looks like it is loading forever.
     */
    private fun showAddPopup(button: com.intellij.ui.AnActionButton) {
        val environmentId = selectedEnvironmentId()
        val free = environmentId?.let { draft.freeKinds(it) }.orEmpty()
        val group = DefaultActionGroup()
        group.add(simpleAction("Environment", AllIcons.Nodes.Folder) { addEnvironment() })
        if (free.isNotEmpty()) {
            group.addSeparator()
            free.forEach { kind ->
                group.add(simpleAction("Flowable ${kind.display} Connection", null) {
                    addConnection(environmentId!!, kind)
                })
            }
        }
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                "Add", group, com.intellij.ide.DataManager.getInstance().getDataContext(tree),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false,
            )
            .show(button.preferredPopupPoint)
    }

    private fun addEnvironment() {
        flushCurrentForm()
        val env = draft.addEnvironment("New Environment")
        rebuildTree(select = Node.Env(env.id))
        // The very next keystroke should rename it — an environment called "New Environment" is a
        // to-do, not a name.
        environmentForm.focusName()
    }

    private fun addConnection(environmentId: String, kind: ConnectionKind) {
        flushCurrentForm()
        val conn = draft.addConnection(environmentId, kind) ?: return
        rebuildTree(select = Node.Conn(conn.id))
    }

    private fun removeSelected() {
        when (val node = selectedNode() ?: return) {
            is Node.Env -> {
                val env = draft.environment(node.id) ?: return
                val connections = draft.connectionsOf(env.id).size
                if (connections > 0) {
                    val answer = Messages.showYesNoDialog(
                        project,
                        "Remove environment \"${env.name}\" and its $connections connection(s)?\n\n" +
                            "Saved passwords and tokens stay in the IDE password safe.",
                        "Remove Environment",
                        "Remove",
                        "Cancel",
                        Messages.getWarningIcon(),
                    )
                    if (answer != Messages.YES) return
                }
                draft.removeEnvironment(env.id)
            }
            is Node.Conn -> draft.removeConnection(node.id)
        }
        loaded = null
        rebuildTree(select = null)
    }

    private fun moveSelected(delta: Int) {
        val node = selectedNode() as? Node.Env ?: return
        flushCurrentForm()
        if (draft.moveEnvironment(node.id, delta)) rebuildTree(select = node)
    }

    private fun copyAction(): AnAction =
        simpleAction("Copy Environment", AllIcons.Actions.Copy) {
            val node = selectedNode()
            val environmentId = when (node) {
                is Node.Env -> node.id
                is Node.Conn -> draft.connection(node.id)?.environmentId
                null -> null
            } ?: return@simpleAction
            flushCurrentForm()
            // "Define once, clone for the next stage": copy QA, rename it UAT, change two URLs.
            draft.copyEnvironment(environmentId)?.let { rebuildTree(select = Node.Env(it.id)) }
            environmentForm.focusName()
        }

    private fun simpleAction(text: String, icon: javax.swing.Icon?, run: () -> Unit): AnAction =
        object : AnAction(text, null, icon) {
            override fun actionPerformed(e: AnActionEvent) = run()

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

    // ---- rendering ----------------------------------------------------------------------------

    private inner class NodeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            when (val node = (value as? DefaultMutableTreeNode)?.userObject) {
                is Node.Env -> {
                    val env = draft.environment(node.id) ?: return
                    append(env.name.ifBlank { "(unnamed)" }, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    // A padlock, not a warning: nothing is wrong with PROD, it is simply guarded.
                    if (env.requireConfirmation) icon = AllIcons.Nodes.Padlock
                    if (draft.connectionsOf(env.id).isEmpty()) {
                        append("  no connections", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
                is Node.Conn -> {
                    val conn = draft.connection(node.id) ?: return
                    val inUse = conn.id == AtlasConnectionSelection.storedId(project, conn.kind)
                    append(
                        conn.kind.display,
                        if (inUse) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES,
                    )
                    append("  ${conn.baseUrl.ifBlank { "no URL yet" }}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    // Bold plus a word, because bold alone is not a legible signal in a tree.
                    if (inUse) append("  in use", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
                else -> Unit
            }
        }
    }

    private companion object {
        const val CARD_EMPTY = "empty"
        const val CARD_ENVIRONMENT = "environment"
        const val CARD_DESIGN = "design"
        const val CARD_WORK = "work"
    }
}
