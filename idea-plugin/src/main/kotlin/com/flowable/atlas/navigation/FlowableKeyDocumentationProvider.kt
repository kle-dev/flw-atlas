package com.flowable.atlas.navigation

import com.flowable.atlas.completion.SiteMatching
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil

/**
 * Shows the model type, name and file when hovering (or Ctrl-Q) over a Flowable key literal at a
 * public-API call site.
 */
class FlowableKeyDocumentationProvider : AbstractDocumentationProvider() {

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? =
        doc(element, originalElement)

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? =
        doc(element, originalElement)

    private fun doc(element: PsiElement?, originalElement: PsiElement?): String? {
        // literal at a key site, or a constant reference used as the key argument
        val (site, key, anchor) = run {
            val literal = literalOf(originalElement) ?: literalOf(element)
            if (literal != null) {
                val s = SiteMatching.keySiteForLiteral(literal) ?: return null
                val k = literal.value as? String ?: return null
                Triple(s, k, literal as PsiElement)
            } else {
                val ref = refOf(originalElement) ?: refOf(element) ?: return null
                val (s, k) = SiteMatching.keySiteForArgument(ref) ?: return null
                Triple(s, k, ref as PsiElement)
            }
        }

        val service = anchor.project.service<FlowableModelIndexService>()
        val entries = service.find(key).filter { it.type in site.targetTypes }
        if (entries.isEmpty()) return null

        val first = entries.first()
        return buildString {
            append("<b>").append(escape(key)).append("</b> &mdash; ").append(first.type.display)
            if (first.name != key) append("<br/>").append(escape(first.name))
            tableLine(service, first.type, key)?.let { append("<br/>").append(it) }
            append("<br/><small>").append(escape(first.file.presentableUrl)).append("</small>")
            if (entries.size > 1) append("<br/><small>+ ").append(entries.size - 1).append(" more file(s)</small>")
        }
    }

    /**
     * The physical table behind the key: read directly for a service key, or via the backing
     * service for a data object key. Lazy per-hover — two small file reads at most.
     */
    private fun tableLine(service: FlowableModelIndexService, type: ModelType, key: String): String? {
        val table = when (type) {
            ModelType.SERVICE -> service.serviceTableOf(key)
            ModelType.DATA_OBJECT -> service.dataObjectInfoOf(key)
                ?.referencedServiceDefinitionModelKey?.let { service.serviceTableOf(it) }
            else -> null
        } ?: return null
        val name = table.tableName ?: return null
        return buildString {
            append("Table: <b>").append(escape(name)).append("</b>")
            table.type?.let { append(" <small>(").append(escape(it)).append(")</small>") }
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
