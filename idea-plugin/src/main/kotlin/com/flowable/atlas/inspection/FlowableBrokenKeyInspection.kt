package com.flowable.atlas.inspection

import com.flowable.atlas.completion.KeySite
import com.flowable.atlas.completion.SiteMatching
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
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiReferenceExpression

/**
 * Flags a Flowable model-key literal that does not match any indexed key of the expected type, e.g.
 *
 *     runtimeService.startProcessInstanceByKey("DEMO-P999")   // no such process key in the project
 *
 * Only reports when the project actually contains keys of that type (so a not-yet-indexed / empty
 * project is never falsely flagged). Offers a quick fix to the closest known key when one is near.
 */
class FlowableBrokenKeyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitLiteralExpression(literal: PsiLiteralExpression) {
                val value = literal.value as? String ?: return
                if (value.isBlank()) return
                val site = SiteMatching.keySiteForLiteral(literal) ?: return
                check(literal, site, value, offerReplaceFix = true)
            }

            // Constant references (`startProcessInstanceByKey(ModelConstants.FOO)`) are the
            // pattern the generated model-constants class encourages — validate them too.
            override fun visitReferenceExpression(ref: PsiReferenceExpression) {
                if (ref.parent !is PsiExpressionList) return
                val (site, value) = SiteMatching.keySiteForArgument(ref) ?: return
                if (value.isBlank()) return
                check(ref, site, value, offerReplaceFix = false)
            }

            private fun check(element: PsiElement, site: KeySite, value: String, offerReplaceFix: Boolean) {
                val service = element.project.service<FlowableModelIndexService>()
                val knownKeys = knownKeys(service, site)
                if (knownKeys.isEmpty()) return          // nothing indexed for this type — don't guess
                if (value in knownKeys) return

                val typeLabel = site.targetTypes.joinToString("/") { it.display }
                val suggestion = Suggestions.closest(value, knownKeys)
                val fixes = if (offerReplaceFix && suggestion != null) {
                    arrayOf<LocalQuickFix>(ReplaceKeyFix(suggestion))
                } else LocalQuickFix.EMPTY_ARRAY
                val hint = suggestion?.let { " — did you mean '$it'?" } ?: ""
                holder.registerProblem(
                    element,
                    "'$value' is not a known $typeLabel key$hint",
                    ProblemHighlightType.WARNING,
                    *fixes,
                )
            }
        }
    }

    private fun knownKeys(service: FlowableModelIndexService, site: KeySite): Set<String> {
        val keys = LinkedHashSet<String>()
        for (type in site.targetTypes) service.keysOfType(type).forEach { keys.add(it.key) }
        return keys
    }

    /** Replaces the flagged key literal with a known key. */
    private class ReplaceKeyFix(private val replacement: String) : LocalQuickFix {
        override fun getFamilyName(): String = "Replace with '$replacement'"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val literal = descriptor.psiElement as? PsiLiteralExpression ?: return
            val factory = JavaPsiFacade.getElementFactory(project)
            literal.replace(factory.createExpressionFromText("\"$replacement\"", literal))
        }
    }
}
