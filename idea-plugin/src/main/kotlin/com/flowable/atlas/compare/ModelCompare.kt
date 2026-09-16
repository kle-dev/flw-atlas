package com.flowable.atlas.compare

import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.model.MiniJson
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.navigation.se.ArchivePaths
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * Opens the platform's diff viewer on one model in two places: the entry inside an app archive on the
 * left — the app as it is — and the file in the project on the right, the candidate.
 *
 * ### Why both sides are re-laid-out by default
 * A Design export is minified — one long line per model, and the same goes for every `.form` in a
 * deployment `.bar`. Compared as it stands against a file a generator (or a formatter) wrote out over
 * hundreds of lines, *everything* differs and the viewer has nothing to say. So when both sides are JSON
 * that parses, each is re-indented through [MiniJson.reindent] — whitespace only, never a value — and the
 * pair is shown read-only, because neither side is then the file on disk. *Show Raw Files* in the diff
 * toolbar gives the files themselves, where the project side stays editable; it opens as its own view, so
 * the re-indented one stays where it was.
 *
 * XML models (`.bpmn`, `.cmmn`, `.dmn`) are exported formatted already and are always shown as they are.
 */
internal object ModelCompare {

    /** A request, and what the toolbar still has to offer for it. */
    data class Prepared(val request: SimpleDiffRequest, val reformatted: Boolean, val canReformat: Boolean)

    /**
     * Prepares off the EDT — both sides are read and laid out, which is file I/O — and shows the result
     * on it. Every way in goes through here, the toolbar switch included, so none of them has to know.
     */
    fun show(project: Project, inArchive: VirtualFile, inProject: VirtualFile, reformat: Boolean = true) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, message("compare.progress.opening"), false) {
                override fun run(indicator: ProgressIndicator) {
                    // A read action because building a content out of a file asks the document manager
                    // for its document; the platform's own diff producers run on this same footing.
                    val prepared = ReadAction.computeBlocking<Prepared, RuntimeException> {
                        prepare(project, inArchive, inProject, reformat)
                    }
                    if (prepared.canReformat) {
                        prepared.request.putUserData(
                            DiffUserDataKeys.CONTEXT_ACTIONS,
                            listOf(SwitchView(project, inArchive, inProject, toReformatted = !prepared.reformatted)),
                        )
                    }
                    ApplicationManager.getApplication().invokeLater({
                        if (!project.isDisposed) DiffManager.getInstance().showDiff(project, prepared.request)
                    }, ModalityState.any())
                }
            },
        )
    }

    /** Everything [show] does except showing it: thread-agnostic, and what a test reads instead of a window. */
    fun prepare(
        project: Project,
        inArchive: VirtualFile,
        inProject: VirtualFile,
        reformat: Boolean,
    ): Prepared {
        val archiveText = laidOut(inArchive)
        val projectText = laidOut(inProject)
        val canReformat = archiveText != null && projectText != null
        val archiveTitle = titleOf(project, inArchive)
        val projectTitle = titleOf(project, inProject)
        val title = "$archiveTitle  ↔  $projectTitle"
        val factory = DiffContentFactory.getInstance()
        if (reformat && canReformat) {
            val request = SimpleDiffRequest(
                title,
                // The third argument is the file the synthetic text is *highlighted* like, which keeps
                // this independent of which language plugins are installed.
                factory.create(project, archiveText!!, inArchive),
                factory.create(project, projectText!!, inProject),
                message("compare.side.reformatted", archiveTitle),
                message("compare.side.reformatted", projectTitle),
            )
            request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
            return Prepared(request, reformatted = true, canReformat = true)
        }
        return Prepared(
            SimpleDiffRequest(
                title,
                factory.create(project, inArchive),
                factory.create(project, inProject),
                archiveTitle,
                projectTitle,
            ),
            reformatted = false,
            canReformat = canReformat,
        )
    }

    /** The file re-indented, or null when it is not JSON that parses — then the bytes are shown as they are. */
    private fun laidOut(file: VirtualFile): String? {
        if (ModelType.isXmlModel(file.name)) return null
        val text = runCatching { String(file.contentsToByteArray(), Charsets.UTF_8) }.getOrNull() ?: return null
        return MiniJson.reindent(text)
    }

    /** `App.zip → form-models/DEMO-F001.json` for an archive entry, the project-relative path otherwise. */
    private fun titleOf(project: Project, file: VirtualFile): String {
        if (file.fileSystem is JarFileSystem) return ArchivePaths.displayPath(file)
        val base = project.basePath
        return if (base != null && file.path.startsWith("$base/")) file.path.removePrefix("$base/")
        else file.presentableUrl
    }

    // Each key spelled literally: a computed bundle key is one nothing can check.
    private fun switchText(toReformatted: Boolean): String =
        if (toReformatted) message("compare.toolbar.reformat") else message("compare.toolbar.raw")

    private fun switchDescription(toReformatted: Boolean): String =
        if (toReformatted) message("compare.toolbar.reformat.description") else message("compare.toolbar.raw.description")

    /**
     * The one toolbar button, labelled with what it will do rather than with a state: from the
     * re-indented view it offers the raw files, from the raw files the re-indented view. It opens as its
     * own view rather than replacing this one, so a comparison you were reading stays where it was.
     */
    private class SwitchView(
        private val project: Project,
        private val inArchive: VirtualFile,
        private val inProject: VirtualFile,
        private val toReformatted: Boolean,
    ) : AnAction(
        ModelCompare.switchText(toReformatted),
        ModelCompare.switchDescription(toReformatted),
        null,
    ),
        DumbAware {

        override fun actionPerformed(e: AnActionEvent) =
            ModelCompare.show(project, inArchive, inProject, reformat = toReformatted)

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }
}
