package com.flowable.atlas.action

import com.flowable.atlas.AtlasNotifications
import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.compare.ArchiveEntryCandidates
import com.flowable.atlas.compare.ArchiveEntryCandidates.ArchiveEntry
import com.flowable.atlas.compare.ModelArchiveMatch
import com.flowable.atlas.compare.ModelCompare
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer

/**
 * *Compare Model with Archive*: the same model side by side, once as the app carries it and once as the
 * project does.
 *
 * The case it exists for is a model generated outside Design — a form written by an LLM, say — parked in
 * the project folder rather than in the app export. "What did this change against the app we have?" is
 * then a diff the IDE can draw, except that one of its two sides lives inside `models/<App>.zip` and
 * nothing offers that pairing; the answer was to unzip by hand into a temp folder.
 *
 * It reads the selection and goes the way that is left:
 *  - a file in the project → the model entries of every `.bar`/`.zip` in scope are ranked
 *    ([ModelArchiveMatch]); one match opens straight away, several open a popup, and no match opens the
 *    same popup over every entry, because "Atlas cannot see it" and "it is called something else" look
 *    identical from the outside;
 *  - an entry inside an archive (*Go to Model* lands in one) → the same ladder over the project's own
 *    model files, and a file chooser when none of them is a plausible counterpart.
 *
 * The archive side is always the left one — the app as it is — and [ModelCompare] owns what the viewer
 * then shows.
 */
class CompareModelWithArchiveAction : AnAction(), DumbAware {

    override fun update(e: AnActionEvent) {
        val file = comparableSelection(e)
        e.presentation.isEnabledAndVisible = e.project != null && file != null
        // Both directions are one action, so the menu has to say which one it is about to take. Each
        // key spelled out: a computed bundle key is one nothing can check.
        if (file != null) {
            e.presentation.text =
                if (isArchiveEntry(file)) message("compare.text.toProject") else message("compare.text.toArchive")
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = comparableSelection(e) ?: return
        if (isArchiveEntry(file)) compareWithProjectFile(project, file) else compareWithArchive(project, file)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /** The one selected model file, or null — two selected files are the platform's own *Compare Files*. */
    private fun comparableSelection(e: AnActionEvent): VirtualFile? {
        val selection = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (selection != null && selection.size > 1) return null
        return e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { ArchiveEntryCandidates.isComparable(it) }
    }

    private fun isArchiveEntry(file: VirtualFile): Boolean = file.fileSystem is JarFileSystem

    private fun compareWithArchive(project: Project, file: VirtualFile) {
        inBackground(project, message("compare.progress.archives")) { indicator ->
            val entries = ArchiveEntryCandidates.archiveEntries(project) { indicator.checkCanceled() }
            val matched = ModelArchiveMatch
                .rank(file.name, keyOf(file), entries) { it.file.name }
                .map { it.value }
            onEdt(project) {
                when {
                    entries.isEmpty() -> info(project, message("compare.noArchives"))
                    matched.size == 1 -> ModelCompare.show(project, matched.first().file, file)
                    // Matches first, then the rest: the list stays complete, so a miss is recoverable
                    // without running the action again. The popup filters as you type.
                    else -> chooseArchiveEntry(project, file, matched + (entries - matched.toSet()))
                }
            }
        }
    }

    private fun compareWithProjectFile(project: Project, entry: VirtualFile) {
        inBackground(project, message("compare.progress.project")) { indicator ->
            val files = ArchiveEntryCandidates.looseModelFiles(project) { indicator.checkCanceled() }
            val matched = ModelArchiveMatch
                .rank(entry.name, keyOf(entry), files) { it.name }
                .map { it.value }
            onEdt(project) {
                when (matched.size) {
                    1 -> ModelCompare.show(project, entry, matched.first())
                    0 -> chooseProjectFileManually(project, entry)
                    else -> chooseProjectFile(project, entry, matched)
                }
            }
        }
    }

    private fun chooseArchiveEntry(project: Project, file: VirtualFile, rows: List<ArchiveEntry>) {
        if (rows.isEmpty()) {
            info(project, message("compare.noModelEntries"))
            return
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(rows)
            .setTitle(message("compare.popup.archive"))
            .setRenderer(textListCellRenderer("") { it.label })
            .setNamerForFiltering { it.label }
            .setItemChosenCallback { row -> ModelCompare.show(project, row.file, file) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun chooseProjectFile(project: Project, entry: VirtualFile, rows: List<VirtualFile>) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(rows)
            .setTitle(message("compare.popup.project"))
            .setRenderer(textListCellRenderer("") { relativeLabel(project, it) })
            .setNamerForFiltering { relativeLabel(project, it) }
            .setItemChosenCallback { row -> ModelCompare.show(project, entry, row) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    /** No plausible counterpart in the project — so ask, rather than report nothing. */
    private fun chooseProjectFileManually(project: Project, entry: VirtualFile) {
        val descriptor = FileChooserDescriptorFactory.singleFile()
            .withTitle(message("compare.chooser.title"))
            .withDescription(message("compare.chooser.description", entry.name))
        val preselect = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val chosen = FileChooser.chooseFile(descriptor, project, preselect) ?: return   // user cancelled
        ModelCompare.show(project, entry, chosen)
    }

    private fun relativeLabel(project: Project, file: VirtualFile): String {
        val base = project.basePath
        return if (base != null && file.path.startsWith("$base/")) file.path.removePrefix("$base/") else file.presentableUrl
    }

    /** The file's own model key, for the last rung of the ladder. Pooled thread: it reads the file. */
    private fun keyOf(file: VirtualFile): String? =
        runCatching { file.contentsToByteArray() }.getOrNull()
            ?.let { ModelArchiveMatch.keyOf(file.name, it) }

    /** A cancelled scan throws out of [work], so nothing is shown — never an empty result instead. */
    private fun inBackground(project: Project, title: String, work: (ProgressIndicator) -> Unit) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) = work(indicator)
        })
    }

    private fun onEdt(project: Project, show: () -> Unit) =
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) show() }, ModalityState.any())

    private fun info(project: Project, text: String) = AtlasNotifications.info(project, text)
}
