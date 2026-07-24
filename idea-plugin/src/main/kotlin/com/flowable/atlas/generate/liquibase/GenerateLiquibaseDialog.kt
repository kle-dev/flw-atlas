package com.flowable.atlas.generate.liquibase

import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.util.regex.PatternSyntaxException
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/** Which set of candidates the dialog previews; the two menu actions preselect one. */
enum class LiquibaseSource { DATA_OBJECTS, APPS }

/**
 * "Generate Liquibase Changelogs" — the single, transparent entry point for both menu actions. Shows,
 * per source (data objects / app exports), a preview table of exactly what will be written: the model
 * key, an editable descriptive name, the origin (extracted from an app vs. synthesized from fields),
 * the resulting file name (rendered live from the pattern + optional regex rename) and whether it is
 * new or overwrites an existing file. Output folder, name pattern and rename are seeded from — and, on
 * OK, saved back to — the project's Generation settings.
 *
 * All heavy resolution happened before construction ([LiquibaseScaffoldService.computePlans], off the
 * EDT); this dialog is pure UI over the resulting [LiquibaseScaffoldService.Plans].
 */
class GenerateLiquibaseDialog(
    private val project: Project,
    private val projectBase: VirtualFile,
    private val plans: LiquibaseScaffoldService.Plans,
    initialSource: LiquibaseSource,
) : DialogWrapper(project) {

    /** A mutable preview row: the resolved [item] plus the user's editable choices and computed columns. */
    private class Row(val item: LiquibaseScaffoldService.ChangelogPlanItem) {
        var include: Boolean = true
        var name: String = item.defaultName
        var fileName: String = ""
        var exists: Boolean = false
    }

    private val settings = FlowableAtlasProjectSettings.getInstance(project)

    private var source = initialSource
    private val rows = ArrayList<Row>()

    private val dataObjectsRadio = JBRadioButton("Data objects")
    private val appsRadio = JBRadioButton("App export(s)")

    private val selectAllLink = ActionLink("Select all") { setAllIncluded(true) }
    private val clearAllLink = ActionLink("Clear") { setAllIncluded(false) }

    private val outputDirField = TextFieldWithBrowseButton()
    private val patternField = JBTextField()
    private val renameFindField = JBTextField()
    private val renameReplaceField = JBTextField()
    private val skipExistingBox = JBCheckBox("Skip files that already exist (keep their current content)")

    private val model = ListTableModel<Row>(IncludeColumn(), KeyColumn(), NameColumn(), OriginColumn(), FileColumn(), StatusColumn())
    private val table = TableView(model)

    init {
        title = "Generate Liquibase Changelogs"
        setOKButtonText("Generate")

        outputDirField.text = settings.liquibaseOutputDir
        outputDirField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select Liquibase Output Folder"),
        )
        patternField.text = settings.liquibaseFileNamePattern
        renameFindField.text = settings.liquibaseRenameFind
        renameReplaceField.text = settings.liquibaseRenameReplace

        ButtonGroup().apply { add(dataObjectsRadio); add(appsRadio) }
        dataObjectsRadio.addActionListener { switchSource(LiquibaseSource.DATA_OBJECTS) }
        appsRadio.addActionListener { switchSource(LiquibaseSource.APPS) }

        outputDirField.textField.onChange { recompute() }
        patternField.onChange { recompute() }
        renameFindField.onChange { recompute() }
        renameReplaceField.onChange { recompute() }

        init()
        switchSource(initialSource)
    }

    // ---- layout -------------------------------------------------------------------------------

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        add(
            JPanel(BorderLayout()).apply {
                add(
                    JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                        add(com.intellij.ui.components.JBLabel("Source:  "))
                        add(dataObjectsRadio)
                        add(appsRadio)
                    },
                    BorderLayout.WEST,
                )
                add(
                    JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply {
                        add(selectAllLink)
                        add(clearAllLink)
                    },
                    BorderLayout.EAST,
                )
            },
            BorderLayout.NORTH,
        )
        add(JBScrollPane(table).apply { preferredSize = Dimension(760, 260) }, BorderLayout.CENTER)
        add(configPanel(), BorderLayout.SOUTH)
    }

    private fun configPanel(): JComponent = panel {
        row("Output folder:") { cell(outputDirField).align(AlignX.FILL) }
        row("File name:") { cell(patternField).align(AlignX.FILL) }
        row {
            comment(
                "Tokens: {key} {name} {service} {servicePrefix} {serviceNo} {table} — {name} is editable " +
                    "per row; {service}/{table} may be empty when no matching data object is indexed.",
            )
        }
        collapsibleGroup("Rename (regex)") {
            row("Find:") { cell(renameFindField).align(AlignX.FILL) }
            row("Replace:") { cell(renameReplaceField).align(AlignX.FILL) }
            row {
                comment("Applied to the rendered name. e.g. Find <code>S0*(\\d+)</code> Replace <code>L\$1</code> turns a KYC-S009 service into …-L9.")
            }
        }
        row { cell(skipExistingBox) }
        separator()
        row {
            comment(
                "Writes the files above and registers each <code>&lt;include&gt;</code> in " +
                    "<code>flowable-project-db-changelog.xml</code> (Flowable runs it automatically). " +
                    "No <code>liquibase.properties</code> or build changes are made.",
            )
        }
    }.apply { border = JBUI.Borders.emptyTop(4) }

    override fun getPreferredFocusedComponent(): JComponent = table

    // ---- behavior -----------------------------------------------------------------------------

    private fun switchSource(newSource: LiquibaseSource) {
        source = newSource
        (if (newSource == LiquibaseSource.DATA_OBJECTS) dataObjectsRadio else appsRadio).isSelected = true
        val plan = if (newSource == LiquibaseSource.DATA_OBJECTS) plans.dataObjects else plans.apps
        rows.clear()
        // App exports default to "extract all"; data objects default to none so the user opts in.
        val defaultInclude = newSource == LiquibaseSource.APPS
        plan.forEach { rows.add(Row(it).apply { include = defaultInclude }) }
        table.emptyText.text = when {
            newSource == LiquibaseSource.DATA_OBJECTS -> "No data objects are indexed in this project."
            else -> "No app export in this project bundles a Liquibase changelog."
        }
        model.items = rows
        recompute()
    }

    /** Re-render the file-name / status columns for every row from the current field values, then revalidate. */
    private fun recompute() {
        val pattern = patternField.text.ifBlank { LiquibaseFileNamePattern.DEFAULT_PATTERN }
        val find = renameFindField.text
        val replace = renameReplaceField.text
        val dir = outputDirVirtualFile()
        for (row in rows) {
            val tokens = LiquibaseFileNamePattern.deriveTokens(row.item.key, row.name, row.item.serviceKey, row.item.tableName)
            val rendered = LiquibaseFileNamePattern.render(pattern, tokens.asMap())
            // A malformed find pattern or replacement (a dangling $/\) throws here; keep the preview
            // alive by falling back to the un-renamed name — doValidate reports the bad find pattern.
            val base = try {
                LiquibaseFileNamePattern.applyRename(rendered, find, replace)
            } catch (e: RuntimeException) {
                rendered
            }
            row.fileName = LiquibaseFileNamePattern.toFileName(base)
            row.exists = dir?.findChild(row.fileName) != null
        }
        model.fireTableDataChanged()
        updateOkButton()
    }

    private fun setAllIncluded(value: Boolean) {
        rows.forEach { it.include = value }
        model.fireTableDataChanged()
        updateOkButton()
    }

    private fun updateOkButton() {
        val n = rows.count { it.include }
        setOKButtonText(if (n > 0) "Generate ($n)" else "Generate")
    }

    private fun outputDirVirtualFile(): VirtualFile? {
        val rel = outputDirField.text.trim().ifBlank { return null }
        return projectBase.findFileByRelativePath(FileUtil.toSystemIndependentName(rel))
    }

    override fun doValidate(): ValidationInfo? {
        val find = renameFindField.text
        if (find.isNotBlank()) {
            try {
                java.util.regex.Pattern.compile(find)
            } catch (e: PatternSyntaxException) {
                return ValidationInfo("Invalid regex: ${e.description ?: e.message}", renameFindField)
            }
        }
        val dir = FileUtil.toSystemIndependentName(outputDirField.text.trim()).trim('/')
        if (dir.isEmpty()) return ValidationInfo("Enter an output folder.", outputDirField)
        if (dir == ".." || dir.startsWith("../") || dir.contains("/../")) {
            return ValidationInfo("The output folder must be inside the project.", outputDirField)
        }
        val included = rows.filter { it.include }
        if (included.isEmpty()) return ValidationInfo("Select at least one changelog to generate.")
        val duplicate = included.groupingBy { it.fileName }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        if (duplicate != null) return ValidationInfo("Two selected rows map to the same file name: $duplicate.")
        return null
    }

    override fun doOKAction() {
        val included = rows.filter { it.include }
        val writes = included.map { LiquibaseScaffoldService.ChangelogWrite(it.fileName, it.item.xml) }
        val outputDir = outputDirField.text.trim()

        settings.liquibaseOutputDir = outputDir
        settings.liquibaseFileNamePattern = patternField.text
        settings.liquibaseRenameFind = renameFindField.text
        settings.liquibaseRenameReplace = renameReplaceField.text

        super.doOKAction()

        val service = LiquibaseScaffoldService.getInstance(project)
        val written = service.writeResolved(projectBase, outputDir, writes, skipExistingBox.isSelected)
        service.reportGenerated(written, included.size - written.size, outputDir)
    }

    private fun JTextComponent.onChange(run: () -> Unit) =
        document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = run()
        })

    // ---- table columns ------------------------------------------------------------------------

    private inner class IncludeColumn : ColumnInfo<Row, Boolean>("") {
        override fun valueOf(row: Row): Boolean = row.include
        override fun getColumnClass(): Class<*> = java.lang.Boolean::class.java
        override fun isCellEditable(row: Row): Boolean = true
        override fun setValue(row: Row, value: Boolean) {
            row.include = value
            updateOkButton()
        }
        override fun getWidth(table: javax.swing.JTable): Int = JBUI.scale(34)
    }

    private inner class KeyColumn : ColumnInfo<Row, String>("Key") {
        override fun valueOf(row: Row): String = row.item.key
    }

    private inner class NameColumn : ColumnInfo<Row, String>("Name") {
        override fun valueOf(row: Row): String = row.name
        override fun isCellEditable(row: Row): Boolean = true
        override fun setValue(row: Row, value: String) {
            row.name = value
            recompute()
        }
    }

    private inner class OriginColumn : ColumnInfo<Row, String>("Origin") {
        override fun valueOf(row: Row): String = row.item.origin.label
    }

    private inner class FileColumn : ColumnInfo<Row, String>("File name") {
        override fun valueOf(row: Row): String = row.fileName
    }

    private inner class StatusColumn : ColumnInfo<Row, String>("Status") {
        override fun valueOf(row: Row): String = if (row.exists) "overwrite" else "new"
        override fun getWidth(table: javax.swing.JTable): Int = JBUI.scale(72)
    }
}
