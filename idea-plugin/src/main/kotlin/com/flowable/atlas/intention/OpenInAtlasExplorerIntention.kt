package com.flowable.atlas.intention

import com.flowable.atlas.completion.SiteMatching
import com.flowable.atlas.completion.ValueKeyMatching
import com.flowable.atlas.explorer.AtlasExplorerFiles
import com.flowable.atlas.explorer.AtlasExplorerNotifier
import com.flowable.atlas.explorer.AtlasExplorerOpener
import com.flowable.atlas.explorer.ExplorerRoutes
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.index.ModelEntry
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.navigation.ModelFileKeySites
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path

/**
 * Alt+Enter on a model key — in Java, or inside a model file (a cross-reference, or the file's own key)
 * → the model's page in the Atlas Explorer, inside the IDE.
 *
 * Ctrl+click on a key opens the model *file* — a minified export or a zip entry, read-only. What a
 * developer usually wants from a key is the relationship view: who calls this process, which forms it
 * opens, what it needs. That page exists in the generated explorer; this is the one step from the literal
 * to it, the reverse of the page's own "open in IDE" button.
 */
class OpenInAtlasExplorerIntention : IntentionAction, DumbAware {

    override fun getText(): String = TEXT
    override fun getFamilyName(): String = TEXT
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        file != null && editor != null && (file is PsiJavaFile || isModelFile(file)) &&
            entryAt(project, file, editor.caretModel.offset) != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (file == null || editor == null) return
        val entry = entryAt(project, file, editor.caretModel.offset) ?: return
        openPage(project, entry)
    }

    // Opens a tab, edits nothing — the default preview would render an empty diff.
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.Html("Opens this model's page in the Atlas Explorer: who references it, what it uses, its findings and its diagram.")

    companion object {
        const val TEXT = "Open in Atlas Explorer"

        /** The model's page in the newest generated explorer, inside the IDE — the Hub's *Recent Models* list uses it too. */
        fun openPage(project: Project, entry: ModelEntry) {
            val base = AtlasProjectRootService.getInstance(project).activeProjectDir() ?: return
            val outputDir = FlowableAtlasProjectSettings.getInstance(project).atlasOutputDir
            val hash = ExplorerRoutes.node(entry.type, entry.key)
            // The search walks the project when the output folder is empty — off the EDT, like the Open action.
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Looking for Atlas explorer files", true) {
                private var files: List<Path> = emptyList()
                override fun run(indicator: ProgressIndicator) { files = AtlasExplorerFiles.find(base, outputDir) }
                override fun onSuccess() {
                    if (project.isDisposed) return
                    val newest = files.firstOrNull() ?: return AtlasExplorerNotifier.notifyNoExplorer(project, outputDir)
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(newest) ?: return
                    AtlasExplorerOpener.openInIde(project, vf, hash)
                }
            })
        }

        /**
         * The model a key at [offset] names, or null — a literal or constant at a Flowable API site (narrowed to
         * the site's types), or any literal whose value is a known key when that recognition is switched on.
         * The cached index only (a background build is requested when there is none): an intention is
         * asked on every Alt+Enter, and must never build one under the read lock.
         */
        fun entryAt(project: Project, file: PsiFile, offset: Int): ModelEntry? {
            val leaf = file.findElementAt(offset) ?: return null
            val (key, types) = (if (isModelFile(file)) modelFileKeyAt(leaf) else keyAt(leaf)) ?: return null
            // The cached index, or a background build for the next Alt+Enter — never a blocking one here.
            // Editing a model file drops the index, so the model-file case would otherwise answer "no" until
            // something else asked for the rebuild.
            val index = project.service<FlowableModelIndexService>().cachedOrRequest() ?: return null
            return index.find(key).firstOrNull { it.type in types }
        }

        private fun isModelFile(file: PsiFile): Boolean =
            file.viewProvider.virtualFile.let { ModelFiles.typeOf(it) != null }

        /** Inside a model file: a cross-reference, or the file's own key — its own page is the most-wanted one. */
        private fun modelFileKeyAt(leaf: PsiElement): Pair<String, Collection<ModelType>>? =
            ModelFileKeySites.at(leaf)?.let { it.key to it.types }

        private fun keyAt(leaf: PsiElement): Pair<String, Collection<ModelType>>? {
            val literal = PsiTreeUtil.getParentOfType(leaf, PsiLiteralExpression::class.java, false)
            if (literal != null) {
                val value = literal.value as? String ?: return null
                SiteMatching.keySiteForLiteral(literal)?.let { return value to it.targetTypes }
                return if (ValueKeyMatching.enabled() && ValueKeyMatching.plausible(value)) value to ModelType.entries else null
            }
            val ref = PsiTreeUtil.getParentOfType(leaf, PsiReferenceExpression::class.java, false) ?: return null
            val (site, value) = SiteMatching.keySiteForArgument(ref) ?: return null
            return value to site.targetTypes
        }
    }
}
