package com.flowable.atlas.generate

import com.flowable.atlas.FlowableAtlasBundle.message
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import org.jetbrains.annotations.VisibleForTesting
import java.awt.BorderLayout
import java.util.regex.PatternSyntaxException
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/**
 * The shape both *Generate* dialogs share: a source picker with *Select All* / *Clear* on the right, a
 * preview table whose first column is the include checkbox and whose last says *new* / *overwrite*, a
 * footer of the generator's own fields, an OK button that counts what it is about to write, and one
 * validation pipeline — the footer's own checks, then "select at least one", then the subclass's row
 * checks, then two rows aimed at the same target.
 *
 * The Liquibase and DTO dialogs were near-twins that had grown apart in small ways: one built its header
 * in `FlowLayout` and the other too but with a combo wedged in, their radio labels disagreed on plurals,
 * their table sizes were unscaled pixel constants. A change to one had to be remembered for the other.
 *
 * Construction order: the subclass initialises its own fields, then calls [start] — that is when the
 * table is built from [columns] and the platform's `init()` lays the dialog out, so nothing here reads a
 * subclass field before it exists.
 */
abstract class GenerateTableDialog<S : Enum<S>, R : GenerateTableDialog.Row>(
    project: Project,
    dialogTitle: String,
    private val sources: List<SourceOption<S>>,
    /** What one row is called in messages: "changelog", "data object". */
    private val itemNoun: String,
    /** What [targetOf] names: "file name", "file". */
    private val targetNoun: String,
) : DialogWrapper(project) {

    /** A preview row: whether it will be written, and whether it already exists where it would land. */
    abstract class Row {
        var include: Boolean = false
        var exists: Boolean = false
        /** False for a row that cannot be written at all — it shows a status and is never ticked. */
        open val generatable: Boolean get() = true
    }

    class SourceOption<S>(val value: S, val label: String)

    protected val rows = ArrayList<R>()
    protected lateinit var source: S
        private set
    protected lateinit var model: ListTableModel<R>
        private set
    protected lateinit var table: TableView<R>
        private set

    private val radios: Map<S, JBRadioButton> = sources.associate { it.value to JBRadioButton(it.label) }
    private val selectAllLink = ActionLink(message("dialog.generate.selectAll")) { setAllIncluded(true) }
    private val clearLink = ActionLink(message("dialog.generate.clear")) { setAllIncluded(false) }
    protected val skipExistingBox = JBCheckBox(message("dialog.generate.skipExisting"))

    init {
        title = dialogTitle
        setOKButtonText(message("dialog.generate.ok"))
        radios.forEach { (value, radio) -> radio.addActionListener { switchSource(value) } }
    }

    // ---- what a subclass provides -------------------------------------------------------------

    /** The table's own columns — the include and status columns are the base's. */
    protected abstract fun columns(): Array<ColumnInfo<R, *>>
    /** The rows for [source]; the base ticks each one per [defaultInclude]. */
    protected abstract fun rowsFor(source: S): List<R>
    protected open fun defaultInclude(source: S, row: R): Boolean = false
    protected abstract fun emptyText(source: S): String
    /** Cells after the radios — the DTO dialog's app combo. */
    protected open fun headerExtras(row: com.intellij.ui.dsl.builder.Row) {}
    protected abstract fun footer(panel: Panel)
    /** Re-render one row's live columns from the footer's current values. */
    protected abstract fun recompute(row: R)
    protected abstract fun keyOf(row: R): String
    /** What two selected rows must not share. */
    protected abstract fun targetOf(row: R): String
    protected open fun statusOf(row: R): String = if (row.exists) "overwrite" else "new"
    protected open fun validateFooter(): ValidationInfo? = null
    protected open fun validateIncluded(included: List<R>): ValidationInfo? = null
    protected open fun duplicateMessage(target: String): String =
        "Two selected rows map to the same $targetNoun: $target."
    protected open fun onSourceSwitched(source: S) {}
    protected abstract fun saveSettings()
    protected abstract fun write(included: List<R>)

    // ---- lifecycle ----------------------------------------------------------------------------

    /** Builds the table and lays the dialog out — the subclass calls this last in its constructor. */
    protected fun start(initial: S) {
        model = ListTableModel<R>(IncludeColumn(), *columns(), StatusColumn())
        table = TableView(model)
        init()
        switchSource(initial)
    }

    /**
     * One panel, top to bottom — the source, the table, the footer's fields — so every label sits in one
     * column. It used to be three separate panels in a border layout, and *Source:*, *Output folder:* and
     * *File name pattern:* each started their field at a different place.
     */
    override fun createCenterPanel(): JComponent = panel {
        // The DSL owns the radios' ButtonGroup — it refuses a pre-built radio outside buttonsGroup.
        buttonsGroup {
            row(message("dialog.generate.source")) {
                radios.values.forEach { cell(it) }
                headerExtras(this)
                cell(selectAllLink).align(AlignX.RIGHT).resizableColumn()
                cell(clearLink)
            }
        }
        // One size for both dialogs, in scaled units — the old Dimension constants ignored HiDPI.
        row { cell(JBScrollPane(table).apply { preferredSize = JBUI.size(820, 280) }).align(Align.FILL) }.resizableRow()
        footer(this)
    }

    override fun getPreferredFocusedComponent(): JComponent = table

    protected fun switchSource(newSource: S) {
        source = newSource
        radios.getValue(newSource).isSelected = true
        onSourceSwitched(newSource)
        rows.clear()
        rowsFor(newSource).forEach { row ->
            row.include = row.generatable && defaultInclude(newSource, row)
            rows.add(row)
        }
        table.emptyText.text = emptyText(newSource)
        model.items = rows
        recomputeAll()
    }

    /** Re-render the live columns of every row, then the OK button. */
    protected fun recomputeAll() {
        rows.forEach { recompute(it) }
        model.fireTableDataChanged()
        updateOkButton()
    }

    private fun setAllIncluded(value: Boolean) {
        rows.forEach { if (it.generatable) it.include = value }
        model.fireTableDataChanged()
        updateOkButton()
    }

    private fun updateOkButton() {
        val n = rows.count { it.include }
        setOKButtonText(if (n > 0) message("dialog.generate.okCount", n) else message("dialog.generate.ok"))
    }

    override fun doValidate(): ValidationInfo? {
        validateFooter()?.let { return it }
        val included = rows.filter { it.include }
        // Anchored to the table, so the message points at the rows it is about rather than floating
        // above the buttons.
        if (included.isEmpty()) return ValidationInfo("Select at least one $itemNoun to generate.", table)
        validateIncluded(included)?.let { return it }
        val duplicate = included.groupingBy { targetOf(it) }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        if (duplicate != null) return ValidationInfo(duplicateMessage(duplicate), table)
        return null
    }

    override fun doOKAction() {
        val included = rows.filter { it.include }
        saveSettings()
        super.doOKAction()
        write(included)
    }

    // ---- helpers for the footers --------------------------------------------------------------

    protected fun JTextComponent.onChange(run: () -> Unit) =
        document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = run()
        })

    /** Null when [field] holds a valid (or blank) regex, else the platform's own description of the fault. */
    protected fun validateRegex(field: JBTextField): ValidationInfo? {
        val find = field.text
        if (find.isBlank()) return null
        return try {
            java.util.regex.Pattern.compile(find); null
        } catch (e: PatternSyntaxException) {
            ValidationInfo("Invalid regex: ${e.description ?: e.message}", field)
        }
    }

    /** The collapsible *Rename (regex)* both generators offer, with the generator's own example. */
    protected fun Panel.renameGroup(find: JBTextField, replace: JBTextField, exampleHtml: String) {
        collapsibleGroup(message("dialog.generate.rename")) {
            row(message("dialog.generate.rename.find")) { cell(find).align(AlignX.FILL) }
            row(message("dialog.generate.rename.replace")) { cell(replace).align(AlignX.FILL) }
            row { comment(exampleHtml) }
        }
    }

    // ---- test hooks ---------------------------------------------------------------------------
    // The preview's wiring is only observable through the UI; these keep it assertable without a dialog.

    @VisibleForTesting
    internal fun includedKeysForTesting(): List<String> = rows.filter { it.include }.map { keyOf(it) }

    @VisibleForTesting
    internal fun selectSourceForTesting(newSource: S) = switchSource(newSource)

    @VisibleForTesting
    internal fun validationMessageForTesting(): String? = doValidate()?.message

    @VisibleForTesting
    internal fun selectAllForTesting() = setAllIncluded(true)

    // ---- the two columns every preview has ----------------------------------------------------

    private inner class IncludeColumn : ColumnInfo<R, Boolean>("") {
        override fun valueOf(row: R): Boolean = row.include
        override fun getColumnClass(): Class<*> = Boolean::class.javaObjectType
        override fun isCellEditable(row: R): Boolean = row.generatable
        override fun setValue(row: R, value: Boolean) {
            row.include = value
            updateOkButton()
        }
        override fun getWidth(table: JTable): Int = JBUI.scale(34)
    }

    private inner class StatusColumn : ColumnInfo<R, String>(message("dialog.generate.status")) {
        override fun valueOf(row: R): String = statusOf(row)
        override fun getWidth(table: JTable): Int = JBUI.scale(78)
    }
}
