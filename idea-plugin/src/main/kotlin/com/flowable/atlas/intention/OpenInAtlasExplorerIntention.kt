package com.flowable.atlas.intention

import com.flowable.atlas.completion.SiteMatching
import com.flowable.atlas.completion.ValueKeyMatching
import com.flowable.atlas.explorer.AtlasExplorerFiles
import com.flowable.atlas.explorer.AtlasExplorerNotifier
import com.flowable.atlas.explorer.AtlasExplorerOpener
import com.flowable.atlas.explorer.ExplorerRoutes
import com.flowable.atlas.index.FlowableIndex
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.index.ModelEntry
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.navigation.ModelFileKeySites
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.flowable.atlas.usage.EndpointModelScan
import com.flowable.atlas.usage.EndpointPsi
import com.flowable.atlas.usage.ModelReferenceScan
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path

/**
 * Alt+Enter on a model key — in Java, or inside a model file (a cross-reference, or the file's own key)
 * → the model's page in the Atlas Explorer, inside the IDE. And on the name of a Java class, method or
 * REST handler that models use → that class's, method's or endpoint's page.
 *
 * Ctrl+click on a key opens the model *file* — a minified export or a zip entry, read-only. What a
 * developer usually wants from a key is the relationship view: who calls this process, which forms it
 * opens, what it needs. That page exists in the generated explorer; this is the one step from the literal
 * to it, the reverse of the page's own "open in IDE" button. The Java side has pages too — a delegate's
 * roles, bean names and the models using it, an endpoint's callers and handler parameters — and the
 * gutter marks on those declarations only list the models.
 */
class OpenInAtlasExplorerIntention : IntentionAction, DumbAware {

    override fun getText(): String = TEXT
    override fun getFamilyName(): String = TEXT
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        file != null && editor != null && (file is PsiJavaFile || isModelFile(file)) &&
            routeAt(project, file, editor.caretModel.offset) != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (file == null || editor == null) return
        val target = routeAt(project, file, editor.caretModel.offset) ?: return
        openRoute(project, target.route)
    }

    // Opens a tab, edits nothing — the default preview would render an empty diff.
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.Html(
            when (routeAt(project, file, editor.caretModel.offset)?.page) {
                Page.CLASS -> "Opens this class's page in the Atlas Explorer: its roles and bean names, the models that use it and its findings."
                Page.METHOD -> "Opens this method's page in the Atlas Explorer: the models that call it and the class that declares it."
                Page.ENDPOINT -> "Opens this endpoint's page in the Atlas Explorer: the models that call it and the parameters its handler takes."
                else -> "Opens this model's page in the Atlas Explorer: who references it, what it uses, its findings and its diagram."
            },
        )

    /** Which kind of page a [Target] opens — what the preview says. */
    enum class Page { MODEL, CLASS, METHOD, ENDPOINT }

    /** An explorer page: its [route] (the hash, without `#`), and what kind of page it is. */
    data class Target(val route: String, val page: Page)

    companion object {
        const val TEXT = "Open in Atlas Explorer"

        /**
         * The page the caret is on: a model key's ([entryAt]), else — in Java, on the name in a declaration —
         * a REST handler's endpoint, a method a model calls, or a class a model uses. Null on anything the
         * models do not know, so the intention is not offered on every class in the project. The cached
         * index only, and each check a set lookup or a cached value: this runs on every Alt+Enter.
         */
        fun routeAt(project: Project, file: PsiFile, offset: Int): Target? {
            entryAt(project, file, offset)?.let { return Target(ExplorerRoutes.node(it.type, it.key), Page.MODEL) }
            if (file !is PsiJavaFile) return null
            // The intention is DumbAware for the key literals above; the declaration side resolves annotation
            // types and searches for beans, which throw while the IDE indexes — no page then, not an error.
            if (DumbService.isDumb(project)) return null
            // with the caret just after the name, the leaf at the offset is the next token
            val name = (file.findElementAt(offset) as? PsiIdentifier)
                ?: (file.findElementAt(offset - 1) as? PsiIdentifier)
                ?: return null
            val index = project.service<FlowableModelIndexService>().cachedOrRequest() ?: return null
            return when (val owner = name.parent) {
                is PsiMethod -> if (owner.nameIdentifier == name) methodTarget(project, owner, index) else null
                is PsiClass -> if (owner.nameIdentifier == name) classTarget(owner, index) else null
                else -> null
            }
        }

        private fun methodTarget(project: Project, method: PsiMethod, index: FlowableIndex): Target? {
            // Every mapping the project serves is an endpoint on the page, called by a model or not. Of a
            // handler mapped to several paths, the one a model calls is the page worth opening.
            val endpoints = EndpointPsi.endpointsOf(method)
            if (endpoints.isNotEmpty()) {
                val all = EndpointPsi.projectEndpoints(project)
                val ep = endpoints.firstOrNull { EndpointModelScan.anyModelCalls(index, it, all) } ?: endpoints.first()
                return Target(ExplorerRoutes.endpoint(ep.verb, ep.path), Page.ENDPOINT)
            }
            val ref = ModelReferenceScan.refOf(method) ?: return null
            if (!ref.usedIn(index)) return null
            val fqcn = method.containingClass?.qualifiedName ?: return null
            // A method gets a page when a model *calls* it by name; a getter read as a property
            // (`${bean.total}`) has none, and its class's page is the place that lists it.
            return if (ref.beans.any { "$it#${method.name}" in index.beanMembers }) Target(ExplorerRoutes.method(fqcn, method.name), Page.METHOD)
            else Target(ExplorerRoutes.javaClass(fqcn), Page.CLASS)
        }

        private fun classTarget(cls: PsiClass, index: FlowableIndex): Target? {
            val fqcn = cls.qualifiedName ?: return null
            val used = ModelReferenceScan.refOf(cls)?.usedIn(index) == true ||
                cls.methods.any { EndpointPsi.isEndpointMethod(it) }
            return if (used) Target(ExplorerRoutes.javaClass(fqcn), Page.CLASS) else null
        }

        /** The model's page in the newest generated explorer, inside the IDE — the Hub's *Recent Models* list uses it too. */
        fun openPage(project: Project, entry: ModelEntry) = openRoute(project, ExplorerRoutes.node(entry.type, entry.key))

        /** Any page of the newest generated explorer, by its route — a model's, a report's (Atlas Findings uses it). */
        fun openRoute(project: Project, hash: String) {
            val base = AtlasProjectRootService.getInstance(project).activeProjectDir() ?: return
            val outputDir = FlowableAtlasProjectSettings.getInstance(project).atlasOutputDir
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
