package com.flowable.atlas.inspection

import com.flowable.atlas.completion.FluentChain
import com.flowable.atlas.completion.FlowableApiCatalog
import com.flowable.atlas.completion.ValueSite
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.InheritanceUtil

/**
 * Flags a `value("field", …)` call on a Flowable data-object query/builder whose field name is not
 * an input value of the selected operation, e.g.
 *
 *     createDataObjectInstanceQuery().definitionKey("DEMO-D010").operation("findAll").value("x", "")
 *
 * → "x" is highlighted because operation "findAll" has no such input value.
 *
 * Only reports when both the data object and the operation resolve (so the valid value set is known).
 */
class FlowableValueFieldInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitLiteralExpression(literal: PsiLiteralExpression) {
                val fieldName = literal.value as? String ?: return

                val argList = literal.parent as? PsiExpressionList ?: return
                if (argList.expressions.indexOf(literal) != 0) return
                val call = argList.parent as? PsiMethodCallExpression ?: return

                val method = call.resolveMethod() ?: return
                val declaring = method.containingClass ?: return
                val site = FlowableApiCatalog.sitesForMethod(method.name)
                    .filterIsInstance<ValueSite>()
                    .firstOrNull { it.argIndex == 0 && isReceiver(declaring.qualifiedName, declaring, it.receiverFqn) }
                    ?: return

                val project = literal.project
                val chain = FluentChain.collectCalls(call)
                val modelKey = FluentChain.findCall(chain, site.keyMethod)
                    ?.let { FluentChain.constantStringArg(it, 0, project) } ?: return
                val operationKey = FluentChain.findCall(chain, site.operationMethod)
                    ?.let { FluentChain.constantStringArg(it, 0, project) } ?: return

                val service = project.service<FlowableModelIndexService>()
                val operations = if (site.keyIsService) service.operationsOfService(modelKey) else service.operationsOf(modelKey)
                val operation = operations.firstOrNull { it.key == operationKey } ?: return

                val validFields = operation.inputParameters.map { it.name }
                if (fieldName !in validFields) {
                    val hint = if (validFields.isEmpty()) "it has no input values" else "expected one of: ${validFields.joinToString(", ")}"
                    val suggestion = Suggestions.closest(fieldName, validFields)
                    val fixes = suggestion?.let { arrayOf<LocalQuickFix>(ReplaceFieldFix(it)) } ?: LocalQuickFix.EMPTY_ARRAY
                    holder.registerProblem(
                        literal,
                        "'$fieldName' is not an input value of operation '$operationKey' on data object '$modelKey' ($hint)",
                        ProblemHighlightType.WARNING,
                        *fixes,
                    )
                }
            }
        }
    }

    private fun isReceiver(declaringFqn: String?, declaring: com.intellij.psi.PsiClass, fqn: String): Boolean =
        declaringFqn == fqn || InheritanceUtil.isInheritor(declaring, fqn)

    /** Replaces the flagged value field with a valid input value of the operation. */
    private class ReplaceFieldFix(private val replacement: String) : LocalQuickFix {
        override fun getFamilyName(): String = "Replace with '$replacement'"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val literal = descriptor.psiElement as? PsiLiteralExpression ?: return
            val factory = JavaPsiFacade.getElementFactory(project)
            literal.replace(factory.createExpressionFromText("\"$replacement\"", literal))
        }
    }
}
