package com.flowable.atlas.usage

import com.flowable.atlas.icons.AtlasIcons
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.navigation.se.ArchivePaths
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import javax.swing.JList

/**
 * Where a gutter mark leads: one model opens directly, several are offered in a chooser whose rows read
 * like the Search Everywhere tab — the type's icon, the key, and the file on the right (`app.zip →
 * processes/x.bpmn` for a packed model). The popup used to list raw absolute paths, which in a project
 * that keeps its models in archives were identical up to the last segment. Speed search matches key and
 * name. Call on the EDT.
 */
object ModelReferenceNavigator {

    /** One row of the chooser. [type] is null only for a file the index does not know (it still opens). */
    internal data class Row(val file: VirtualFile, val type: ModelType?, val key: String, val name: String, val where: String)

    fun show(project: Project, files: List<VirtualFile>, title: String, at: RelativePoint?) {
        if (project.isDisposed) return
        val rows = rows(project, files)
        when (rows.size) {
            0 -> {}
            1 -> open(project, rows[0])
            else -> {
                val popup = JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(rows)
                    .setTitle(title)
                    .setRenderer(RowRenderer())
                    .setNamerForFiltering { "${it.key} ${it.name}" }
                    .setItemChosenCallback { open(project, it) }
                    .createPopup()
                if (at != null) popup.show(at) else popup.showInFocusCenter()
            }
        }
    }

    /** The rows for [files]: one per indexed key in each file, model types in their canonical order. */
    internal fun rows(project: Project, files: List<VirtualFile>): List<Row> {
        val byFile = project.service<FlowableModelIndexService>().cachedOrNull()?.allEntries()?.groupBy { it.file }.orEmpty()
        val out = LinkedHashMap<Pair<VirtualFile, String>, Row>()
        for (file in files.filter { it.isValid }) {
            val entries = byFile[file].orEmpty()
            if (entries.isEmpty()) {
                out.putIfAbsent(file to file.name, Row(file, ModelFiles.typeOf(file), file.nameWithoutExtension, "", ArchivePaths.displayPath(file)))
                continue
            }
            for (e in entries.distinctBy { it.key }) {
                out.putIfAbsent(file to e.key, Row(file, e.type, e.key, e.name, ArchivePaths.displayPath(file)))
            }
        }
        return out.values.sortedWith(compareBy({ it.type?.ordinal ?: Int.MAX_VALUE }, { it.key }))
    }

    private fun open(project: Project, row: Row) {
        if (row.file.isValid) FileEditorManager.getInstance(project).openFile(row.file, true)
    }

    /** Type icon · key · grey file — one fact per column, the way the Search Everywhere row reads. */
    internal class RowRenderer : ColoredListCellRenderer<Row>() {
        override fun customizeCellRenderer(list: JList<out Row>, value: Row, index: Int, selected: Boolean, hasFocus: Boolean) {
            icon = value.type?.let(AtlasIcons::forType) ?: AtlasIcons.Model
            append(value.key, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append("  ${value.where}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}
