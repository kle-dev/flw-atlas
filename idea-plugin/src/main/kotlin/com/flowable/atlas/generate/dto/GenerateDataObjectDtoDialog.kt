package com.flowable.atlas.generate.dto

import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.generate.GenerateTableDialog
import com.flowable.atlas.generate.JavaSourceRoots
import com.flowable.atlas.intention.DataObjectBeanGenerator
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.VisibleForTesting
import javax.swing.DefaultComboBoxModel
import javax.swing.JTable

/**
 * "Generate Data-Object DTOs" — the single, transparent entry point for both menu actions. Shows, per
 * source (hand-picked data objects / a whole app), a preview table of exactly what will be written:
 * the data-object key, the class name (rendered live from the class-name pattern, editable per row),
 * the owning app, the field count, the resulting source-root-relative file and whether it is new or
 * overwrites an existing class. Target source root, package, class-name pattern, optional regex rename
 * and the per-app nesting are seeded from — and, on OK, saved back to — the project's Generation
 * settings.
 *
 * All heavy resolution happened before construction ([DataObjectDtoService.computePlans], off the
 * EDT); this dialog is pure UI over the resulting [DataObjectDtoService.Plans], on the shape
 * [GenerateTableDialog] gives both generators.
 */
class GenerateDataObjectDtoDialog(
    private val project: Project,
    private val plans: DataObjectDtoService.Plans,
    initialSource: DtoSource,
) : GenerateTableDialog<DtoSource, GenerateDataObjectDtoDialog.Row>(
    project,
    message("dialog.generateDto.title"),
    listOf(
        SourceOption(DtoSource.DATA_OBJECTS, "Data objects"),
        SourceOption(DtoSource.APPS, "Apps"),
    ),
    itemNoun = "data object",
    targetNoun = "file",
) {

    /** A mutable preview row: the resolved [item], the app it is nested under, and the user's choices. */
    class Row(val item: DataObjectDtoService.DtoPlanItem, val app: AppRef?) : GenerateTableDialog.Row() {
        var className: String = item.defaultClassName
        var path: String = ""
        /** True once the class name was typed in the table: the pattern stops overwriting this row. */
        var classNameEdited: Boolean = false
        override val generatable: Boolean get() = item.generatable
    }

    /** An entry of the app combo; a null [ref] is the "all apps" entry. */
    private class AppOption(val ref: AppRef?, val label: String) {
        override fun toString(): String = label
    }

    private val settings = FlowableAtlasProjectSettings.getInstance(project)

    /** What the `{suffix}` token renders; configured on the Data-Object DTOs settings page, not in this dialog. */
    private val classSuffix = settings.dtoClassSuffix

    private val appCombo = ComboBox<AppOption>()
    private val sourceRootCombo = ComboBox<VirtualFile>()
    private val browseLink = ActionLink("Browse…") { browseForSourceRoot() }
    private val packageField = JBTextField()
    private val classPatternField = JBTextField()
    private val renameFindField = JBTextField()
    private val renameReplaceField = JBTextField()
    private val perAppBox = JBCheckBox("Sub-package per app")

    init {
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
        appCombo.addActionListener { if (source == DtoSource.APPS) switchSource(DtoSource.APPS) }

        sourceRootCombo.addActionListener { recomputeAll() }
        sourceRootCombo.renderer = textListCellRenderer("") { root -> JavaSourceRoots.displayPath(project, root) }
        packageField.onChange { recomputeAll() }
        classPatternField.onChange { recomputeAll() }
        renameFindField.onChange { recomputeAll() }
        renameReplaceField.onChange { recomputeAll() }
        perAppBox.addActionListener { recomputeAll() }

        start(if (plans.apps.isEmpty()) DtoSource.DATA_OBJECTS else initialSource)
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

    override fun columns(): Array<ColumnInfo<Row, *>> =
        arrayOf(KeyColumn(), ClassColumn(), AppColumn(), FieldsColumn(), FileColumn())

    override fun headerExtras(row: com.intellij.ui.dsl.builder.Row) {
        row.cell(appCombo)
    }

    override fun onSourceSwitched(source: DtoSource) {
        appCombo.isEnabled = source == DtoSource.APPS && plans.apps.isNotEmpty()
    }

    override fun rowsFor(source: DtoSource): List<Row> {
        val selectedApp = (appCombo.selectedItem as? AppOption)?.ref
        val items = when {
            source == DtoSource.DATA_OBJECTS -> plans.items
            selectedApp != null -> plans.itemsOfApp(selectedApp.key)
            else -> plans.items.filter { it.apps.isNotEmpty() }
        }
        return items.map { Row(it, selectedApp ?: it.primaryApp) }
    }

    /** A whole app is an explicit "all of it" request; hand-picking starts empty so the user opts in. */
    override fun defaultInclude(source: DtoSource, row: Row): Boolean = source == DtoSource.APPS

    override fun emptyText(source: DtoSource): String = when {
        source == DtoSource.APPS && plans.apps.isEmpty() ->
            "No app in this project lists data objects — use the \"Data objects\" source."
        source == DtoSource.APPS -> "This app lists no data objects."
        else -> "No data objects are indexed in this project."
    }

    override fun footer(panel: Panel) {
        with(panel) {
            row("Target source root:") {
                cell(sourceRootCombo).align(AlignX.FILL)
                cell(browseLink)
            }
            row("Package:") { cell(packageField).align(AlignX.FILL) }
            row { comment("Leave the package empty to write straight into the source root.") }
            row("Class name pattern:") { cell(classPatternField).align(AlignX.FILL) }
            row {
                comment(
                    "Tokens: {name} {shortName} {key} {app} {suffix} — {name} is the model name in " +
                        "PascalCase, <b>{shortName} the same without the leading model key</b> " +
                        "(<code>DEMO-D009 Pod Member</code> → <code>PodMember</code>, not " +
                        "<code>DEMOD009PodMember</code>), {suffix} the class-name suffix from Settings → " +
                        "Tools → Flowable Atlas → Generation → Data-Object DTOs. Type a class name in the table to override the pattern " +
                        "for that row.",
                )
            }
            renameGroup(
                renameFindField, renameReplaceField,
                "Applied to the rendered class name, for what the tokens can't express. e.g. Find <code>^DEMO(\\w+)</code> Replace <code>Demo\$1</code> turns DEMOCustomerDto into DemoCustomerDto.",
            )
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
        }
    }

    /** A row whose class name the user typed keeps it — the pattern only drives the rows it still owns. */
    override fun recompute(row: Row) {
        if (!row.classNameEdited) {
            val tokens = DtoClassNamePattern.deriveTokens(row.item.key, row.item.modelName, row.app?.key, classSuffix)
            row.className = DtoClassNamePattern.className(classPatternField.text, tokens, renameFindField.text, renameReplaceField.text)
        }
        val pkg = DataObjectDtoPlanner.packageFor(packageField.text, row.app?.key, perAppBox.isSelected)
        row.path = DataObjectDtoPlanner.targetPath(pkg, row.className)
        row.exists = selectedSourceRoot()?.findFileByRelativePath(row.path) != null
    }

    override fun keyOf(row: Row): String = row.item.key
    override fun targetOf(row: Row): String = row.path
    override fun statusOf(row: Row): String = if (!row.item.generatable) "no fields" else super.statusOf(row)
    override fun duplicateMessage(target: String): String =
        "Two selected rows map to the same file: $target. Rename one, or nest per app."

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

    override fun validateFooter(): ValidationInfo? {
        if (selectedSourceRoot() == null) return ValidationInfo("Select a target source root.", sourceRootCombo)
        if (!DataObjectDtoPlanner.isValidPackage(packageField.text)) {
            return ValidationInfo("'${packageField.text.trim()}' is not a valid Java package.", packageField)
        }
        return validateRegex(renameFindField)
    }

    override fun validateIncluded(included: List<Row>): ValidationInfo? {
        included.firstOrNull { it.className.isBlank() }?.let {
            return ValidationInfo("The class name renders empty for row ${it.item.key}.", classPatternField)
        }
        included.firstOrNull { !DataObjectDtoPlanner.isValidClassName(it.className) }?.let {
            return ValidationInfo("'${it.className}' is not a valid Java class name (row ${it.item.key}).", table)
        }
        return null
    }

    override fun saveSettings() {
        selectedSourceRoot()?.let { settings.dtoSourceRootUrl = it.url }
        settings.dtoPackage = packageField.text
        settings.dtoClassNamePattern = classPatternField.text
        settings.dtoRenameFind = renameFindField.text
        settings.dtoRenameReplace = renameReplaceField.text
        settings.dtoPackagePerApp = perAppBox.isSelected
    }

    override fun write(included: List<Row>) {
        val root = selectedSourceRoot() ?: return
        val writes = included.map { row ->
            val pkg = DataObjectDtoPlanner.packageFor(packageField.text, row.app?.key, perAppBox.isSelected)
            DataObjectDtoService.DtoWrite(
                path = row.path,
                source = DataObjectBeanGenerator.generate(pkg.ifBlank { null }, row.className, row.item.key, row.item.fields),
            )
        }
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
    internal fun selectSourceForTesting(newSource: DtoSource, appKey: String?) {
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
        packageField.text = packageName   // fires the document listener → recomputeAll()
        recomputeAll()
    }

    @VisibleForTesting
    internal fun configurePatternForTesting(pattern: String, renameFind: String = "", renameReplace: String = "") {
        classPatternField.text = pattern
        renameFindField.text = renameFind
        renameReplaceField.text = renameReplace
        recomputeAll()
    }

    @VisibleForTesting
    internal fun classNamesForTesting(): List<Pair<String, String>> = rows.map { it.item.key to it.className }

    @VisibleForTesting
    internal fun editClassNameForTesting(key: String, className: String) {
        val index = rows.indexOfFirst { it.item.key == key }
        model.setValueAt(className, index, model.columnInfos.indexOfFirst { it is ClassColumn })
    }

    // ---- table columns ------------------------------------------------------------------------

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
            recomputeAll()
        }
    }

    private inner class AppColumn : ColumnInfo<Row, String>("App") {
        override fun valueOf(row: Row): String = row.item.appLabel
    }

    private inner class FieldsColumn : ColumnInfo<Row, String>("Fields") {
        override fun valueOf(row: Row): String = row.item.fields.size.toString()
        override fun getWidth(table: JTable): Int = JBUI.scale(56)
    }

    private inner class FileColumn : ColumnInfo<Row, String>("Target file") {
        override fun valueOf(row: Row): String = row.path
    }
}
