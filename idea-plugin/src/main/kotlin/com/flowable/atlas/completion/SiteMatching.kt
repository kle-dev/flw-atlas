package com.flowable.atlas.completion

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.InheritanceUtil

/**
 * Shared resolution of "which Flowable API call-site (if any) is this argument?", used by the
 * completion contributor, the broken-key inspection, the reference contributor and the docs
 * provider so the matching rules live in one place.
 */
object SiteMatching {

    /** The [ApiSite] for argument [argIndex] of [call], or null if the call isn't a catalog site. */
    fun siteAt(call: PsiMethodCallExpression, argIndex: Int): ApiSite? {
        if (argIndex < 0) return null
        val method = call.resolveMethod() ?: return null
        val declaring = method.containingClass ?: return null
        return FlowableApiCatalog.sitesForMethod(method.name).firstOrNull {
            it.argIndex == argIndex && isReceiver(declaring, it.receiverFqn)
        }
    }

    /**
     * If [literal] is a `String` argument at a Flowable [KeySite], returns that site (so callers can
     * validate / navigate / document the key). Null otherwise.
     */
    fun keySiteForLiteral(literal: PsiLiteralExpression): KeySite? {
        if (literal.value !is String) return null
        val argList = literal.parent as? PsiExpressionList ?: return null
        val call = argList.parent as? PsiMethodCallExpression ?: return null
        val argIndex = argList.expressions.indexOf(literal)
        return siteAt(call, argIndex) as? KeySite
    }

    fun isReceiver(declaring: PsiClass, fqn: String): Boolean =
        declaring.qualifiedName == fqn || InheritanceUtil.isInheritor(declaring, fqn)
}
