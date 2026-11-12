package com.flowable.atlas.intention

import com.flowable.atlas.completion.SiteMatching
import com.flowable.atlas.completion.ValueKeyMatching
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil

/**
 * "Which Flowable data object does the caret sit on?" — the single resolver behind the data-object
 * intentions. Two ways a key is recognized, strongest first:
 *
 *  1. **The call site.** The expression is the key argument of a catalogued data-object API call
 *     (`definitionKey(…)` and friends). [SiteMatching.keySiteForArgument] resolves both an inline
 *     literal *and* a constant reference, so `definitionKey(ModelKeys.CUSTOMER)` — exactly what the
 *     plugin's own "Generate Model Constants" produces — is recognized like `definitionKey("…")`.
 *  2. **The value.** Any other String literal or String constant whose value *is* an indexed
 *     data-object key. A key held in a `private static final String` or passed to a helper method is
 *     just as much a data-object key as one typed into the query builder.
 *
 * Deliberately cheap: the value path is an O(1) hit against the **already-built** index
 * ([FlowableModelIndexService.cachedOrNull]) — never a scan — because this runs on every
 * intention-availability pass. Case 1 does not require the index at all: the call site is proof of
 * intent, so the intention stays available on a cold index and resolves the model when invoked.
 */
object DataObjectKeyAtCaret {

    /** The data-object key [element] denotes, or null. Never builds the model index. */
    fun resolve(project: Project, element: PsiElement): String? {
        val expr = keyExpressionAt(element) ?: return null
        SiteMatching.keySiteForArgument(expr)?.let { (site, value) ->
            if (ModelType.DATA_OBJECT in site.targetTypes) return value
        }
        val value = SiteMatching.constantValueOf(expr) ?: return null
        if (!ValueKeyMatching.plausible(value)) return null
        // Availability is asked on every caret move: the cached index only, no request — the startup
        // warm-up and every other consumer see to it that one exists.
        val index = project.service<FlowableModelIndexService>().cachedOrNull() ?: return null
        return value.takeIf { index.find(it, ModelType.DATA_OBJECT) != null }
    }

    /**
     * The literal / constant-reference expression around [element]. A caret on the *qualifier*
     * (`ModelKeys` in `ModelKeys.CUSTOMER`) walks out to the whole reference, so the key resolves
     * wherever inside the expression the caret happens to be.
     */
    private fun keyExpressionAt(element: PsiElement): PsiExpression? {
        var expr = PsiTreeUtil.getParentOfType(element, PsiExpression::class.java, false) ?: return null
        while (true) {
            val parent = expr.parent
            if (parent is PsiReferenceExpression && parent.qualifierExpression === expr) expr = parent else break
        }
        return expr.takeIf { it is PsiLiteralExpression || it is PsiReferenceExpression }
    }
}
