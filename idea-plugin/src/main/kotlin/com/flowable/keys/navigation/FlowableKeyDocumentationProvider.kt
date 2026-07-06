package com.flowable.keys.navigation

import com.flowable.keys.completion.SiteMatching
import com.flowable.keys.index.FlowableModelIndexService
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
            append("<br/><small>").append(escape(first.file.presentableUrl)).append("</small>")
            if (entries.size > 1) append("<br/><small>+ ").append(entries.size - 1).append(" more file(s)</small>")
        }
    }

    private fun literalOf(element: PsiElement?): PsiLiteralExpression? =
        PsiTreeUtil.getParentOfType(element, PsiLiteralExpression::class.java, false)

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
