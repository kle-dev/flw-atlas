package com.flowable.atlas.generate.dto

import com.flowable.atlas.generate.JavaSourceRoots
import com.flowable.atlas.intention.DataObjectBeanGenerator
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import org.jetbrains.annotations.VisibleForTesting
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.util.regex.PatternSyntaxException
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/**
 * "Generate Data-Object DTOs" — the single, transparent entry point for both menu actions. Shows, per
 * source (a whole app / hand-picked data objects), a preview table of exactly what will be written:
 * the data-object key, the class name (rendered live from the class-name pattern, editable per row),
 * the owning app, the field count, the resulting source-root-relative file and whether it is new or
 * overwrites an existing class. Target source root, package, class-name pattern, optional regex rename
 * and the per-app nesting are seeded from — and, on OK, saved back to — the project's Generation
 * settings (its Liquibase sibling works the same way).
 *
 * All heavy resolution happened before construction ([DataObjectDtoService.computePlans], off the
 * EDT); this dialog is pure UI over the resulting [DataObjectDtoService.Plans].
 */
class GenerateDataObjectDtoDialog(
    private val project: Project,
    private val plans: DataObjectDtoService.Plans,
    initialSource: DtoSource,
) : DialogWrapper(project) {

    /** A mutable preview row: the resolved [item], the app it is nested under, and the user's choices. */
    private class Row(val item: DataObjectDtoService.DtoPlanItem, val app: AppRef?) {
        var include: Boolean = false
        var className: String = item.defaultClassName
        var path: String = ""
        var exists: Boolean = false

        /** True once the class name was typed in the table: the pattern stops overwriting this row. */
        var classNameEdited: Boolean = false
    }

    /** An entry of the app combo; a null [ref] is the "all apps" entry. */
    private class AppOption(val ref: AppRef?, val label: String) {
        override fun toString(): String = label
    }

    private val settings = FlowableAtlasProjectSettings.getInstance(project)

    /** What the `{suffix}` token renders; configured in Settings → Generation, not in this dialog. */
    private val classSuffix = settings.dtoClassSuffix

    private var source = initialSource
    private val rows = ArrayList<Row>()

    private val appsRadio = JBRadioButton("App(s)")
    private val dataObjectsRadio = JBRadioButton("Data objects")
    private val appCombo = ComboBox<AppOption>()

    private val selectAllLink = ActionLink("Select all") { setAllIncluded(true) }
    private val clearAllLink = ActionLink("Clear") { setAllIncluded(false) }

    private val sourceRootCombo = ComboBox<VirtualFile>()
    private val browseLink = ActionLink("Browse…") { browseForSourceRoot() }
    private val packageField = JBTextField()
    private val classPatternField = JBTextField()
    private val renameFindField = JBTextField()
    private val renameReplaceField = JBTextField()
    private val perAppBox = JBCheckBox("Sub-package per app")
    private val skipExistingBox = JBCheckBox("Skip files that already exist (keep their current content)")

    private val model = ListTableModel<Row>(
        IncludeColumn(), KeyColumn(), ClassColumn(), AppColumn(), FieldsColumn(), FileColumn(), StatusColumn(),
    )
    private val table = TableView(model)

    init {
        title = "Generate Data-Object DTOs"
        setOKButtonText("Generate")

        packageField.text = settings.dtoPackage
        classPatternField.text = settings.dtoClassNamePattern
        renameFindField.text = settings.dtoRenameFind
        renameReplaceField.text = settings.dtoRenameReplace
        perAppBox.isSelected = settings.dtoPackagePerApp
        initSourceRoots()

        appCombo.model = DefaultComboBoxModel(
            (listOf(AppOption(null, "All apps (${plans.apps.size})")) + plans.apps.map { AppOption(it, appLabel(it)) })
                .toTypedArray(),
        )
        appCombo.addActionListener { if (source == DtoSource.APPS) refreshRows() }

        ButtonGroup().apply { add(appsRadio); add(dataObjectsRadio) }
        appsRadio.addActionListener { switchSource(DtoSource.APPS) }
        dataObjectsRadio.addActionListener { switchSource(DtoSource.DATA_OBJECTS) }

        sourceRootCombo.addActionListener { recompute() }
        sourceRootCombo.renderer = textListCellRenderer("") { root ->
            JavaSourceRoots.displayPath(project, root)
        }
        packageField.onChange { recompute() }
        classPatternField.onChange { recompute() }
        renameFindField.onChange { recompute() }
        renameReplaceField.onChange { recompute() }
        perAppBox.addActionListener { recompute() }

        init()
        switchSource(if (plans.apps.isEmpty()) DtoSource.DATA_OBJECTS else initialSource)
    }

    /** The known source roots (main-Java first), plus the remembered one and the project dir as fallback. */
    private fun initSourceRoots() {
        val roots = LinkedHashSet<VirtualFile>()
        settings.dtoSourceRootUrl.takeIf { it.isNotBlank() }
            ?.let { VirtualFileManager.getInstance().findFileByUrl(it) }
            ?.takeIf { it.isValid && it.isDirectory }
            ?.let { roots.add(it) }
        roots.addAll(JavaSourceRoots.all(project))
        JavaSourceRoots.projectDir(project)?.let { roots.add(it) }
        sourceRootCombo.model = DefaultComboBoxModel(roots.toTypedArray())
        if (roots.isNotEmpty()) sourceRootCombo.selectedIndex = 0
    }

    private fun appLabel(app: AppRef): String {
        val count = plans.itemsOfApp(app.key).size
        return "${app.label} — $count data object${if (count == 1) "" else "s"}"
    }

    // ---- layout -------------------------------------------------------------------------------

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        add(
            JPanel(BorderLayout()).apply {
                add(
                    JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                        add(JBLabel("Source:  "))
                        add(appsRadio)
                        add(dataObjectsRadio)
                        add(JBLabel("   "))
                        add(appCombo)
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
        add(JBScrollPane(table).apply { preferredSize = Dimension(820, 280) }, BorderLayout.CENTER)
        add(configPanel(), BorderLayout.SOUTH)
    }

    private fun configPanel(): JComponent = panel {
        row("Target source root:") {
            cell(sourceRootCombo).align(AlignX.FILL)
            cell(browseLink)
        }
        row("Package:") { cell(packageField).align(AlignX.FILL) }
        row {
            comment("Leave the package empty to write straight into the source root.")
        }
        row("Class name:") { cell(classPatternField).align(AlignX.FILL) }
        row {
            comment(
                "Tokens: {name} {shortName} {key} {app} {suffix} — {name} is the model name in " +
                    "PascalCase, <b>{shortName} the same without the leading model key</b> " +
                    "(<code>DEMO-D009 Pod Member</code> → <code>PodMember</code>, not " +
                    "<code>DEMOD009PodMember</code>), {suffix} the class-name suffix from Settings → " +
                    "Flowable Atlas → Generation. Type a class name in the table to override the pattern " +
                    "for that row.",
            )
        }
        collapsibleGroup("Rename (regex)") {
            row("Find:") { cell(renameFindField).align(AlignX.FILL) }
            row("Replace:") { cell(renameReplaceField).align(AlignX.FILL) }
            row {
                comment("Applied to the rendered class name, for what the tokens can't express. e.g. Find <code>^DEMO(\\w+)</code> Replace <code>Demo\$1</code> turns DEMOCustomerDto into DemoCustomerDto.")
            }
        }
        row { cell(perAppBox) }
        row { cell(skipExistingBox) }
        separator()
        row {
            comment(
                "Writes one <code>*.java</code> per selected data object — fields, a " +
                    "<code>fromContainer(…)</code> mapper and a fluent builder. Only files under the " +
                    "target source root are created; no build file is touched.",
            )
        }
    }.apply { border = JBUI.Borders.emptyTop(4) }

    override fun getPreferredFocusedComponent(): JComponent = table

    // ---- behavior -----------------------------------------------------------------------------

    private fun switchSource(newSource: DtoSource) {
        source = newSource
        (if (newSource == DtoSource.APPS) appsRadio else dataObjectsRadio).isSelected = true
        appCombo.isEnabled = newSource == DtoSource.APPS && plans.apps.isNotEmpty()
        refreshRows()
    }

    /** Rebuild the rows for the current source; app rows start ticked, hand-picked ones do not. */
    private fun refreshRows() {
        val selectedApp = (appCombo.selectedItem as? AppOption)?.ref
        val items = when {
            source == DtoSource.DATA_OBJECTS -> plans.items
            selectedApp != null -> plans.itemsOfApp(selectedApp.key)
            else -> plans.items.filter { it.apps.isNotEmpty() }
        }
        rows.clear()
        // A whole app is an explicit "all of it" request; hand-picking starts empty so the user opts in.
        val defaultInclude = source == DtoSource.APPS
        items.forEach { item ->
            rows.add(Row(item, selectedApp ?: item.primaryApp).apply { include = defaultInclude && item.generatable })
        }
        table.emptyText.text = when {
            source == DtoSource.APPS && plans.apps.isEmpty() ->
                "No app in this project lists data objects — use the \"Data objects\" source."
            source == DtoSource.APPS -> "This app lists no data objects."
            else -> "No data objects are indexed in this project."
        }
        model.items = rows
        recompute()
    }

    /**
     * Re-render the class-name / file / status columns for every row from the current field values, then
     * revalidate. A row whose class name the user typed keeps it — the pattern only drives the rows it
     * still owns.
     */
    private fun recompute() {
        val root = selectedSourceRoot()
        val pattern = classPatternField.text
        val find = renameFindField.text
        val replace = renameReplaceField.text
        for (row in rows) {
            if (!row.classNameEdited) {
                val tokens = DtoClassNamePattern.deriveTokens(
                    row.item.key, row.item.modelName, row.app?.key, classSuffix,
                )
                row.className = DtoClassNamePattern.className(pattern, tokens, find, replace)
            }
            val pkg = DataObjectDtoPlanner.packageFor(packageField.text, row.app?.key, perAppBox.isSelected)
            row.path = DataObjectDtoPlanner.targetPath(pkg, row.className)
            row.exists = root?.findFileByRelativePath(row.path) != null
        }
        model.fireTableDataChanged()
        updateOkButton()
    }

    private fun setAllIncluded(value: Boolean) {
        rows.forEach { if (it.item.generatable) it.include = value }
        model.fireTableDataChanged()
        updateOkButton()
    }

    private fun updateOkButton() {
        val n = rows.count { it.include }
        setOKButtonText(if (n > 0) "Generate ($n)" else "Generate")
    }

    private fun selectedSourceRoot(): VirtualFile? = sourceRootCombo.selectedItem as? VirtualFile

    private fun browseForSourceRoot() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Select Target Source Root for Generated DTOs")
        val chosen = FileChooser.chooseFile(descriptor, project, selectedSourceRoot()) ?: return
        val comboModel = sourceRootCombo.model as DefaultComboBoxModel<VirtualFile>
        if ((0 until comboModel.size).none { comboModel.getElementAt(it) == chosen }) {
            comboModel.insertElementAt(chosen, 0)
        }
        sourceRootCombo.selectedItem = chosen
    }

    override fun doValidate(): ValidationInfo? {
        if (selectedSourceRoot() == null) return ValidationInfo("Select a target source root.", sourceRootCombo)
        if (!DataObjectDtoPlanner.isValidPackage(packageField.text)) {
            return ValidationInfo("'${packageField.text.trim()}' is not a valid Java package.", packageField)
        }
        val find = renameFindField.text
        if (find.isNotBlank()) {
            try {
                java.util.regex.Pattern.compile(find)
            } catch (e: PatternSyntaxException) {
                return ValidationInfo("Invalid regex: ${e.description ?: e.message}", renameFindField)
            }
        }
        val included = rows.filter { it.include }
        if (included.isEmpty()) return ValidationInfo("Select at least one data object to generate.")
        included.firstOrNull { it.className.isBlank() }?.let {
            return ValidationInfo("The class name renders empty for row ${it.item.key}.", classPatternField)
        }
        included.firstOrNull { !DataObjectDtoPlanner.isValidClassName(it.className) }?.let {
            return ValidationInfo("'${it.className}' is not a valid Java class name (row ${it.item.key}).")
        }
        val duplicate = included.groupingBy { it.path }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        if (duplicate != null) {
            return ValidationInfo("Two selected rows map to the same file: $duplicate. Rename one, or nest per app.")
        }
        return null
    }

    override fun doOKAction() {
        val root = selectedSourceRoot() ?: return
        val included = rows.filter { it.include }
        val writes = included.map { row ->
            val pkg = DataObjectDtoPlanner.packageFor(packageField.text, row.app?.key, perAppBox.isSelected)
            DataObjectDtoService.DtoWrite(
                path = row.path,
                source = DataObjectBeanGenerator.generate(
                    pkg.ifBlank { null }, row.className, row.item.key, row.item.fields,
                ),
            )
        }

        settings.dtoSourceRootUrl = root.url
        settings.dtoPackage = packageField.text
        settings.dtoClassNamePattern = classPatternField.text
        settings.dtoRenameFind = renameFindField.text
        settings.dtoRenameReplace = renameReplaceField.text
        settings.dtoPackagePerApp = perAppBox.isSelected

        super.doOKAction()

        val service = DataObjectDtoService.getInstance(project)
        val written = service.writeResolved(root, writes, skipExistingBox.isSelected)
        service.reportGenerated(written, included.size - written.size, JavaSourceRoots.displayPath(project, root))
    }

    // ---- test hooks ---------------------------------------------------------------------------
    // The preview's wiring (init order, source switch, live path rendering) is only observable
    // through the UI; these keep it assertable without opening a dialog.

    @VisibleForTesting
    internal fun previewForTesting(): List<Pair<String, String>> = rows.map { it.item.key to it.path }

    @VisibleForTesting
    internal fun includedKeysForTesting(): List<String> = rows.filter { it.include }.map { it.item.key }

    @VisibleForTesting
    internal fun selectSourceForTesting(newSource: DtoSource, appKey: String? = null) {
        appKey?.let { key ->
            val model = appCombo.model
            (0 until model.size).map { model.getElementAt(it) }.firstOrNull { it.ref?.key == key }
                ?.let { appCombo.selectedItem = it }
        }
        switchSource(newSource)
    }

    @VisibleForTesting
    internal fun configureForTesting(packageName: String, perApp: Boolean) {
        perAppBox.isSelected = perApp
        packageField.text = packageName   // fires the document listener → recompute()
        recompute()
    }

    @VisibleForTesting
    internal fun configurePatternForTesting(pattern: String, renameFind: String = "", renameReplace: String = "") {
        classPatternField.text = pattern
        renameFindField.text = renameFind
        renameReplaceField.text = renameReplace
        recompute()
    }

    @VisibleForTesting
    internal fun classNamesForTesting(): List<Pair<String, String>> = rows.map { it.item.key to it.className }

    @VisibleForTesting
    internal fun editClassNameForTesting(key: String, className: String) {
        val index = rows.indexOfFirst { it.item.key == key }
        model.setValueAt(className, index, model.columnInfos.indexOfFirst { it is ClassColumn })
    }

    @VisibleForTesting
    internal fun validationMessageForTesting(): String? = doValidate()?.message

    private fun JTextComponent.onChange(run: () -> Unit) =
        document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = run()
        })

    // ---- table columns ------------------------------------------------------------------------

    private inner class IncludeColumn : ColumnInfo<Row, Boolean>("") {
        override fun valueOf(row: Row): Boolean = row.include
        override fun getColumnClass(): Class<*> = java.lang.Boolean::class.java
        override fun isCellEditable(row: Row): Boolean = row.item.generatable
        override fun setValue(row: Row, value: Boolean) {
            row.include = value
            updateOkButton()
        }
        override fun getWidth(table: javax.swing.JTable): Int = JBUI.scale(34)
    }

    private inner class KeyColumn : ColumnInfo<Row, String>("Key") {
        override fun valueOf(row: Row): String = row.item.key
    }

    private inner class ClassColumn : ColumnInfo<Row, String>("Class name") {
        override fun valueOf(row: Row): String = row.className
        override fun isCellEditable(row: Row): Boolean = row.item.generatable
        override fun setValue(row: Row, value: String) {
            row.className = value.trim()
            // A typed name outranks the pattern from here on; clearing the cell hands the row back.
            row.classNameEdited = row.className.isNotEmpty()
            recompute()
        }
    }

    private inner class AppColumn : ColumnInfo<Row, String>("App") {
        override fun valueOf(row: Row): String = row.item.appLabel
    }

    private inner class FieldsColumn : ColumnInfo<Row, String>("Fields") {
        override fun valueOf(row: Row): String = row.item.fields.size.toString()
        override fun getWidth(table: javax.swing.JTable): Int = JBUI.scale(56)
    }

    private inner class FileColumn : ColumnInfo<Row, String>("Target file") {
        override fun valueOf(row: Row): String = row.path
    }

    private inner class StatusColumn : ColumnInfo<Row, String>("Status") {
        override fun valueOf(row: Row): String = when {
            !row.item.generatable -> "no fields"
            row.exists -> "overwrite"
            else -> "new"
        }
        override fun getWidth(table: javax.swing.JTable): Int = JBUI.scale(78)
    }
}
