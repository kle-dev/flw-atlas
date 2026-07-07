package com.flowable.atlas.completion

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable

/**
 * Helpers for reasoning about a fluent builder/query chain such as
 * `svc.createDataObjectInstanceQuery().definitionKey(X).operation(Y).value("f", …)`.
 */
object FluentChain {

    /** All method calls that make up the fluent chain containing [call], in any order. */
    fun collectCalls(call: PsiMethodCallExpression): List<PsiMethodCallExpression> {
        // Walk up to the outermost call in the chain.
        var top = call
        while (true) {
            val parent = top.parent
            if (parent is PsiReferenceExpression) {
                val grand = parent.parent
                if (grand is PsiMethodCallExpression &&
                    grand.methodExpression === parent &&
                    grand.methodExpression.qualifierExpression === top
                ) {
                    top = grand
                    continue
                }
            }
            break
        }
        // Walk down the qualifier chain, collecting every call.
        val result = ArrayList<PsiMethodCallExpression>()
        var current: PsiExpression? = top
        while (current is PsiMethodCallExpression) {
            result.add(current)
            current = current.methodExpression.qualifierExpression
        }
        return result
    }

    /** The first call in [chain] invoking a method named [methodName], if any. */
    fun findCall(chain: List<PsiMethodCallExpression>, methodName: String): PsiMethodCallExpression? =
        chain.firstOrNull { it.methodExpression.referenceName == methodName }

    /**
     * The compile-time constant String value of argument [argIndex] of [call], resolving
     * constant references too (e.g. `ModelConstants.SHOPPING_LIST` → "DEMO-D010"). Null if the
     * argument is missing or not a constant String.
     */
    fun constantStringArg(call: PsiMethodCallExpression, argIndex: Int, project: Project): String? {
        val arg = call.argumentList.expressions.getOrNull(argIndex) ?: return null
        val helper = JavaPsiFacade.getInstance(project).constantEvaluationHelper
        (helper.computeConstantExpression(arg) as? String)?.let { return it }
        // Fallback for constant references such as ModelConstants.SHOPPING_LIST. In completion
        // contexts computeConstantExpression/computeConstantValue can return null even though the
        // field resolves, so read the resolved field's literal initializer directly.
        if (arg is PsiReferenceExpression) {
            val resolved = arg.resolve()
            if (resolved is PsiVariable) {
                (resolved.computeConstantValue() as? String)?.let { return it }
                val init = resolved.initializer
                if (init is PsiLiteralExpression) (init.value as? String)?.let { return it }
                (helper.computeConstantExpression(init) as? String)?.let { return it }
            }
        }
        return null
    }
}
