package com.flowable.atlas.findings

import com.flowable.atlas.graph.CheckCatalog
import javax.swing.tree.DefaultMutableTreeNode

/**
 * The findings as a tree: *Defects*, then *Advice*, then *Accepted*; under each the checks in the
 * catalog's reading order; under each check its findings. Advice and accepted findings can be left out —
 * Atlas's own rule is that it points out errors first, and advice is what a team may choose to ignore.
 */
internal object FindingsTree {

    sealed interface Item
    data class Group(val title: String, val count: Int) : Item
    data class CheckItem(val id: String, val title: String, val count: Int) : Item
    data class FindingItem(val finding: Map<String, Any?>) : Item {
        val message: String get() = finding["message"] as? String ?: ""
        val label: String get() = finding["label"] as? String ?: finding["node"] as? String ?: ""
        val file: String? get() = finding["file"] as? String
        val line: Int? get() = (finding["line"] as? Number)?.toInt()
        val isError: Boolean get() = finding["severity"] == "error"
    }

    fun build(findings: List<Map<String, Any?>>, showAdvice: Boolean, showAccepted: Boolean): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode()
        val open = findings.filter { it["waived"] == null }
        fun group(title: String, items: List<Map<String, Any?>>) {
            if (items.isEmpty()) return
            val g = DefaultMutableTreeNode(Group(title, items.size))
            val byCheck = items.groupBy { it["check"]?.toString().orEmpty() }
            for (id in CheckCatalog.ORDER + (byCheck.keys - CheckCatalog.ORDER.toSet())) {
                val list = byCheck[id] ?: continue
                val c = DefaultMutableTreeNode(CheckItem(id, CheckCatalog[id]?.title ?: id, list.size))
                list.forEach { c.add(DefaultMutableTreeNode(FindingItem(it))) }
                g.add(c)
            }
            root.add(g)
        }
        group("Defects", open.filter { CheckCatalog.kind(it["check"]?.toString().orEmpty()) != CheckCatalog.ADVICE })
        if (showAdvice) group("Advice", open.filter { CheckCatalog.kind(it["check"]?.toString().orEmpty()) == CheckCatalog.ADVICE })
        if (showAccepted) group("Accepted", findings.filter { it["waived"] != null })
        return root
    }
}
