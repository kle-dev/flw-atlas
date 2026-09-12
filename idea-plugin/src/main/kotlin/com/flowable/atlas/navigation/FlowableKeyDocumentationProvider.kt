package com.flowable.atlas.navigation

import com.flowable.atlas.completion.SiteMatching
import com.flowable.atlas.completion.ValueKeyMatching
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.index.ModelEntry
import com.flowable.atlas.navigation.se.ArchivePaths
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.project.Project
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil

/**
 * Shows the model type, name, backing table and file when hovering (or Ctrl-Q) over a Flowable key —
 * a literal at a public-API call site in Java, a cross-reference or the file's own key inside a model
 * file (BPMN/CMMN XML, or the JSON models) — as a documentation card, the platform's own shape.
 */
class FlowableKeyDocumentationProvider : AbstractDocumentationProvider() {

    // The one-line popup (Ctrl+hover): key, type and name — the card is for Ctrl-Q.
    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? =
        resolve(element, originalElement)?.let { (entries, key) ->
            val first = entries.first()
            buildString {
                append("<b>").append(escape(key)).append("</b> — ").append(first.type.display)
                if (first.name != key) append(" · ").append(escape(first.name))
            }
        }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val (entries, key, service, project) = resolve(element, originalElement) ?: return null
        val first = entries.first()
        // The platform's own card shape — definition, content, a table of sections — so the hover reads
        // like the one over a Java symbol instead of a hand-built stack of <br>s.
        return buildString {
            append(DocumentationMarkup.DEFINITION_START)
            append("<b>").append(escape(key)).append("</b> — ").append(first.type.display)
            append(DocumentationMarkup.DEFINITION_END)
            if (first.name != key) {
                append(DocumentationMarkup.CONTENT_START).append(escape(first.name)).append(DocumentationMarkup.CONTENT_END)
            }
            append(DocumentationMarkup.SECTIONS_START)
            tableLine(service, first.type, key)?.let { section("Table", it) }
            section("File", escape(presentablePath(project, first.file)))
            if (entries.size > 1) section("Also in", "${entries.size - 1} more file(s)")
            append(DocumentationMarkup.SECTIONS_END)
        }
    }

    private fun StringBuilder.section(title: String, html: String) {
        append(DocumentationMarkup.SECTION_HEADER_START).append(title).append(':')
        append(DocumentationMarkup.SECTION_SEPARATOR).append("<p>").append(html)
        append(DocumentationMarkup.SECTION_END)
    }

    /** Project-relative for a loose file, `app.zip → entry` for a packed one; never an absolute path. */
    private fun presentablePath(project: Project, file: VirtualFile): String {
        if (file.fileSystem is JarFileSystem) return ArchivePaths.displayPath(file)
        val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        return base?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.presentableUrl
    }

    private data class Resolved(val entries: List<ModelEntry>, val key: String, val service: FlowableModelIndexService, val project: Project)

    private fun resolve(element: PsiElement?, originalElement: PsiElement?): Resolved? {
        // literal at a key site, or a constant reference used as the key argument
        val project = (originalElement ?: element)?.project ?: return null
        val service = project.service<FlowableModelIndexService>()
        val (targetTypes, key, _) = run {
            // Inside a model file: a cross-reference (calledElement, an eventType's text, a data object's
            // backing service, …) or the file's own key. The leaf under the caret first — the element the
            // platform passes is the reference's *target*, a token in another file.
            val fileSite = ModelFileKeySites.at(originalElement) ?: ModelFileKeySites.at(element)
            if (fileSite != null) {
                if (service.cachedOrNull() == null) service.ensureBuilding()   // hover again once it exists
                return@run Triple(fileSite.types.toSet(), fileSite.key, (originalElement ?: element) as PsiElement)
            }
            val literal = literalOf(originalElement) ?: literalOf(element)
            if (literal != null) {
                val k = literal.value as? String ?: return null
                val site = SiteMatching.keySiteForLiteral(literal)
                if (service.cachedOrNull() == null) service.ensureBuilding()   // hover again once it exists
                when {
                    site != null -> Triple(site.targetTypes, k, literal as PsiElement)
                    // Fallback: a plain data-object key string (a constant's value / bare literal),
                    // even outside a recognised Flowable API call site — so hovering a data-object
                    // key constant still shows its name and physical table. Always on.
                    service.cachedOrNull()?.find(k, ModelType.DATA_OBJECT) != null ->
                        Triple(setOf(ModelType.DATA_OBJECT), k, literal as PsiElement)
                    // Opt-in: recognize any model key by value, so hover works on a key literal anywhere.
                    ValueKeyMatching.enabled() && service.cachedOrNull()?.find(k)?.isNotEmpty() == true ->
                        Triple(ModelType.entries.toSet(), k, literal as PsiElement)
                    else -> return null
                }
            } else {
                val ref = refOf(originalElement) ?: refOf(element) ?: return null
                val (s, k) = SiteMatching.keySiteForArgument(ref) ?: return null
                Triple(s.targetTypes, k, ref as PsiElement)
            }
        }

        // the cached index or nothing: a hover must never build the index under the read lock
        val entries = (service.cachedOrNull() ?: return null).find(key).filter { it.type in targetTypes }
        if (entries.isEmpty()) return null
        return Resolved(entries, key, service, project)
    }

    /**
     * The physical table behind the key: read directly for a service key, or via the backing
     * service for a data object key. Lazy per-hover — two small file reads at most.
     */
    private fun tableLine(service: FlowableModelIndexService, type: ModelType, key: String): String? {
        val table = when (type) {
            ModelType.SERVICE -> service.cachedServiceTableOf(key)
            ModelType.DATA_OBJECT -> service.cachedDataObjectInfoOf(key)
                ?.referencedServiceDefinitionModelKey?.let { service.cachedServiceTableOf(it) }
            else -> null
        } ?: return null
        val name = table.tableName ?: return null
        return buildString {
            append("<b>").append(escape(name)).append("</b>")
            table.type?.let { append(" (").append(escape(it)).append(')') }
        }
    }

    private fun literalOf(element: PsiElement?): PsiLiteralExpression? =
        PsiTreeUtil.getParentOfType(element, PsiLiteralExpression::class.java, false)

    /** The innermost reference expression sitting directly in an argument list. */
    private fun refOf(element: PsiElement?): PsiReferenceExpression? {
        var ref = PsiTreeUtil.getParentOfType(element, PsiReferenceExpression::class.java, false)
        while (ref != null && ref.parent is PsiReferenceExpression) ref = ref.parent as PsiReferenceExpression
        return ref?.takeIf { it.parent is PsiExpressionList }
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
