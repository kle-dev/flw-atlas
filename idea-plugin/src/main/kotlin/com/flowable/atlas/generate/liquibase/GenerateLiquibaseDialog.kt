package com.flowable.atlas.generate.liquibase

import java.io.File
import java.nio.file.Path
import com.flowable.atlas.settings.relativeToProject
import com.flowable.atlas.project.AtlasProjectRootService
import javax.swing.JTextField
import com.intellij.openapi.ui.TextComponentAccessor
import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.generate.GenerateTableDialog
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.ui.ColumnInfo

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
 * EDT); this dialog is pure UI over the resulting [LiquibaseScaffoldService.Plans], on the shape
 * [GenerateTableDialog] gives both generators.
 */
class GenerateLiquibaseDialog(
    private val project: Project,
    private val projectBase: VirtualFile,
    private val plans: LiquibaseScaffoldService.Plans,
    initialSource: LiquibaseSource,
) : GenerateTableDialog<LiquibaseSource, GenerateLiquibaseDialog.Row>(
    project,
    message("dialog.generateLiquibase.title"),
    listOf(
        SourceOption(LiquibaseSource.DATA_OBJECTS, "Data objects"),
        SourceOption(LiquibaseSource.APPS, "App exports"),
    ),
    itemNoun = "changelog",
    targetNoun = "file name",
) {

    private val LOG = logger<GenerateLiquibaseDialog>()

    /** A mutable preview row: the resolved [item] plus the user's editable name and the computed file. */
    class Row(val item: LiquibaseScaffoldService.ChangelogPlanItem) : GenerateTableDialog.Row() {
        var name: String = item.defaultName
        var fileName: String = ""
    }

    private val settings = FlowableAtlasProjectSettings.getInstance(project)

    private val outputDirField = TextFieldWithBrowseButton()
    private val patternField = JBTextField()
    private val renameFindField = JBTextField()
    private val renameReplaceField = JBTextField()

    init {
        outputDirField.text = settings.liquibaseOutputDir
        // Written project-relative, like every folder field on the settings pages: the default listener
        // wrote the absolute path, which the settings page then refused and the "exists" column could not
        // resolve — every row read "new".
        outputDirField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select Liquibase Output Folder"),
            object : TextComponentAccessor<JTextField> {
                override fun getText(component: JTextField): String = component.text
                override fun setText(component: JTextField, text: String) {
                    val base = AtlasProjectRootService.getInstance(project).activeProjectDir()
                    component.text = if (base != null) relativeToProject(base, Path.of(text)) else text
                }
            },
        )
        patternField.text = settings.liquibaseFileNamePattern
        renameFindField.text = settings.liquibaseRenameFind
        renameReplaceField.text = settings.liquibaseRenameReplace

        outputDirField.textField.onChange { recomputeAll() }
        patternField.onChange { recomputeAll() }
        renameFindField.onChange { recomputeAll() }
        renameReplaceField.onChange { recomputeAll() }

        start(initialSource)
    }

    override fun columns(): Array<ColumnInfo<Row, *>> = arrayOf(KeyColumn(), NameColumn(), OriginColumn(), FileColumn())

    override fun rowsFor(source: LiquibaseSource): List<Row> =
        (if (source == LiquibaseSource.DATA_OBJECTS) plans.dataObjects else plans.apps).map(::Row)

    /** App exports default to "extract all"; data objects default to none so the user opts in. */
    override fun defaultInclude(source: LiquibaseSource, row: Row): Boolean = source == LiquibaseSource.APPS

    override fun emptyText(source: LiquibaseSource): String = when (source) {
        LiquibaseSource.DATA_OBJECTS -> "No data objects are indexed in this project."
        LiquibaseSource.APPS -> "No app export in this project ships a Liquibase changelog."
    }

    override fun footer(panel: Panel) {
        with(panel) {
            row("Output folder:") { cell(outputDirField).align(AlignX.FILL) }
            row("File name pattern:") { cell(patternField).align(AlignX.FILL) }
            row {
                comment(
                    "Tokens: {key} {name} {service} {servicePrefix} {serviceNo} {table} — {name} is editable " +
                        "per row; {service}/{table} may be empty when no matching data object is indexed.",
                )
            }
            renameGroup(
                renameFindField, renameReplaceField,
                "Applied to the rendered name. e.g. Find <code>S0*(\\d+)</code> Replace <code>L\$1</code> turns a DEMO-S009 service into …-L9.",
            )
            row { cell(skipExistingBox) }
            separator()
            row {
                comment(
                    "Writes the files above and registers each <code>&lt;include&gt;</code> in " +
                        "<code>flowable-project-db-changelog.xml</code> (Flowable runs it automatically). " +
                        "No <code>liquibase.properties</code> or build changes are made.",
                )
            }
        }
    }

    override fun recompute(row: Row) {
        val pattern = patternField.text.ifBlank { LiquibaseFileNamePattern.DEFAULT_PATTERN }
        val tokens = LiquibaseFileNamePattern.deriveTokens(row.item.key, row.name, row.item.serviceKey, row.item.tableName)
        val rendered = LiquibaseFileNamePattern.render(pattern, tokens.asMap())
        // doValidate() already rejects an invalid regex, so reaching the catch means the pattern is valid
        // but blew up on this particular name (a dangling $ in the replacement). The preview shows the
        // un-renamed result, so the user can see it — the log says why it differs.
        val base = try {
            LiquibaseFileNamePattern.applyRename(rendered, renameFindField.text, renameReplaceField.text)
        } catch (e: RuntimeException) {
            LOG.debug("Rename regex '${renameFindField.text}' failed on '$rendered' — keeping the un-renamed name", e)
            rendered
        }
        row.fileName = LiquibaseFileNamePattern.toFileName(base)
        row.exists = outputDirVirtualFile()?.findChild(row.fileName) != null
    }

    override fun keyOf(row: Row): String = row.item.key
    override fun targetOf(row: Row): String = row.fileName

    private fun outputDirVirtualFile(): VirtualFile? {
        val rel = outputDirField.text.trim().ifBlank { return null }
        return projectBase.findFileByRelativePath(FileUtil.toSystemIndependentName(rel))
    }

    override fun validateFooter(): ValidationInfo? {
        validateRegex(renameFindField)?.let { return it }
        val typed = outputDirField.text.trim()
        // Before the trim below, which turns "/Users/…" into a harmless-looking "Users/…".
        if (File(typed).isAbsolute || typed.startsWith("~")) {
            return ValidationInfo("The output folder must be relative to the project directory.", outputDirField)
        }
        val dir = FileUtil.toSystemIndependentName(typed).trim('/')
        if (dir.isEmpty()) return ValidationInfo("Enter an output folder.", outputDirField)
        if (dir == ".." || dir.startsWith("../") || dir.contains("/../")) {
            return ValidationInfo("The output folder must be inside the project.", outputDirField)
        }
        return null
    }

    override fun saveSettings() {
        settings.liquibaseOutputDir = outputDirField.text.trim()
        settings.liquibaseFileNamePattern = patternField.text
        settings.liquibaseRenameFind = renameFindField.text
        settings.liquibaseRenameReplace = renameReplaceField.text
    }

    override fun write(included: List<Row>) {
        val outputDir = outputDirField.text.trim()
        val writes = included.map { LiquibaseScaffoldService.ChangelogWrite(it.fileName, it.item.xml) }
        val service = LiquibaseScaffoldService.getInstance(project)
        val written = service.writeResolved(projectBase, outputDir, writes, skipExistingBox.isSelected)
        service.reportGenerated(written, included.size - written.size, outputDir)
    }

    // ---- test hooks ---------------------------------------------------------------------------

    internal fun fileNamesForTesting(): List<Pair<String, String>> = rows.map { it.item.key to it.fileName }

    internal fun setOutputDirForTesting(dir: String) {
        outputDirField.text = dir
    }

    internal fun configureForTesting(pattern: String, renameFind: String = "", renameReplace: String = "") {
        patternField.text = pattern
        renameFindField.text = renameFind
        renameReplaceField.text = renameReplace
        recomputeAll()
    }

    // ---- table columns ------------------------------------------------------------------------

    private inner class KeyColumn : ColumnInfo<Row, String>("Key") {
        override fun valueOf(row: Row): String = row.item.key
    }

    private inner class NameColumn : ColumnInfo<Row, String>("Name") {
        override fun valueOf(row: Row): String = row.name
        override fun isCellEditable(row: Row): Boolean = true
        override fun setValue(row: Row, value: String) {
            row.name = value
            recomputeAll()
        }
    }

    private inner class OriginColumn : ColumnInfo<Row, String>("Origin") {
        override fun valueOf(row: Row): String = row.item.origin.label
    }

    private inner class FileColumn : ColumnInfo<Row, String>("File name") {
        override fun valueOf(row: Row): String = row.fileName
    }
}
