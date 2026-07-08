package com.flowable.atlas.navigation

import com.flowable.atlas.completion.SiteMatching
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
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
        val literal = literalOf(originalElement) ?: literalOf(element) ?: return null
        val site = SiteMatching.keySiteForLiteral(literal) ?: return null
        val key = literal.value as? String ?: return null

        val service = literal.project.service<FlowableModelIndexService>()
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

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
