package com.flowable.atlas.settings

import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import javax.swing.DefaultCellEditor
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.table.TableCellEditor

/**
 * One editable table over the three allowlist buckets of [FlowableAtlasProjectSettings]
 * (namespaces / functions / grounding roots). One table with a Kind column beats three separate
 * lists: entries added by the Alt-Enter quick-fix land in one visible place, and users rarely know
 * a priori which bucket an entry belongs to.
 */
class AllowlistTablePanel {

    enum class Kind(val display: String) {
        NAMESPACE("Namespace"),
        FUNCTION("Function"),
        GROUNDING_ROOT("Grounding root");

        override fun toString(): String = display
    }

    class Entry(var text: String = "", var kind: Kind = Kind.FUNCTION)

    private val entryColumn = object : ColumnInfo<Entry, String>("Entry") {
        override fun valueOf(item: Entry): String = item.text
        override fun isCellEditable(item: Entry): Boolean = true
        override fun setValue(item: Entry, value: String) { item.text = value.trim() }
    }

    private val kindColumn = object : ColumnInfo<Entry, Kind>("Kind") {
        override fun valueOf(item: Entry): Kind = item.kind
        override fun isCellEditable(item: Entry): Boolean = true
        override fun setValue(item: Entry, value: Kind) { item.kind = value }
        override fun getEditor(item: Entry): TableCellEditor =
            DefaultCellEditor(JComboBox(Kind.entries.toTypedArray()))
        override fun getMaxStringValue(): String = Kind.GROUNDING_ROOT.display
    }

    private val model = ListTableModel<Entry>(entryColumn, kindColumn)
    private val table = TableView(model).apply {
        setShowGrid(false)
        emptyText.text = "No allowlisted entries — add one, or use Alt-Enter on a finding"
    }

    val component: JComponent = ToolbarDecorator.createDecorator(table)
        .setAddAction { stopEditing(); model.addRow(Entry()) ; editLastRow() }
        .setRemoveAction { stopEditing(); table.selectedRows.sortedDescending().forEach(model::removeRow) }
        .disableUpDownActions()
        .createPanel()

    private fun stopEditing() {
        table.cellEditor?.stopCellEditing()
    }

    private fun editLastRow() {
        val row = model.rowCount - 1
        table.selectionModel.setSelectionInterval(row, row)
        table.editCellAt(row, 0)
    }

    fun reset(settings: FlowableAtlasProjectSettings) {
        val items = buildList {
            settings.allowedNamespaces.forEach { add(Entry(it, Kind.NAMESPACE)) }
            settings.allowedFunctions.forEach { add(Entry(it, Kind.FUNCTION)) }
            settings.allowedGroundingRoots.forEach { add(Entry(it, Kind.GROUNDING_ROOT)) }
        }
        model.items = items
    }

    fun isModified(settings: FlowableAtlasProjectSettings): Boolean {
        stopEditing()
        val (ns, fn, roots) = split()
        return ns != settings.allowedNamespaces || fn != settings.allowedFunctions ||
            roots != settings.allowedGroundingRoots
    }

    fun apply(settings: FlowableAtlasProjectSettings) {
        stopEditing()
        val (ns, fn, roots) = split()
        settings.allowedNamespaces = ns.toMutableList()
        settings.allowedFunctions = fn.toMutableList()
        settings.allowedGroundingRoots = roots.toMutableList()
    }

    private fun split(): Triple<List<String>, List<String>, List<String>> {
        val valid = model.items.filter { it.text.isNotBlank() }
        return Triple(
            valid.filter { it.kind == Kind.NAMESPACE }.map { it.text }.distinct(),
            valid.filter { it.kind == Kind.FUNCTION }.map { it.text }.distinct(),
            valid.filter { it.kind == Kind.GROUNDING_ROOT }.map { it.text }.distinct(),
        )
    }
}
