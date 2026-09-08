package com.flowable.atlas.inspection

import com.flowable.atlas.completion.KeySite
import com.flowable.atlas.completion.SiteMatching
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable

/**
 * Flags a Flowable model-key literal that does not match any indexed key of the expected type, e.g.
 *
 *     runtimeService.startProcessInstanceByKey("DEMO-P999")   // no such process key in the project
 *
 * Only reports when the project actually contains keys of that type (so a not-yet-indexed / empty
 * project is never falsely flagged). Offers a quick fix to the closest known key when one is near — on a
 * literal it replaces the literal, on a constant reference it changes the constant's initializer.
 */
class FlowableBrokenKeyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : JavaElementVisitor() {
            override fun visitLiteralExpression(literal: PsiLiteralExpression) {
                val value = literal.value as? String ?: return
                if (value.isBlank()) return
                val site = SiteMatching.keySiteForLiteral(literal) ?: return
                check(literal, site, value) { ReplaceStringLiteralFix(it, ReplaceStringLiteralFix.KNOWN_MODEL_KEY) }
            }

            // Constant references (`startProcessInstanceByKey(ModelConstants.FOO)`) are the
            // pattern the generated model-constants class encourages — validate them too.
            override fun visitReferenceExpression(ref: PsiReferenceExpression) {
                if (ref.parent !is PsiExpressionList) return
                val (site, value) = SiteMatching.keySiteForArgument(ref) ?: return
                if (value.isBlank()) return
                // The fix edits the constant's declaration — offered only when that literal is there to edit.
                check(ref, site, value) { suggestion ->
                    val literal = ChangeConstantValueFix.initializerLiteral(ref) ?: return@check null
                    val constant = (ref.resolve() as? PsiVariable)?.name ?: return@check null
                    ChangeConstantValueFix(suggestion, constant, literal.containingFile.name)
                }
            }

            private fun check(element: PsiElement, site: KeySite, value: String, fixFor: (String) -> LocalQuickFix?) {
                val service = element.project.service<FlowableModelIndexService>()
                val knownKeys = knownKeys(service, site)
                if (knownKeys.isEmpty()) return          // nothing indexed for this type — don't guess
                if (value in knownKeys) return

                val typeLabel = site.targetTypes.joinToString("/") { it.display }
                val suggestion = Suggestions.closest(value, knownKeys)
                val fixes = suggestion?.let(fixFor)?.let { arrayOf(it) } ?: LocalQuickFix.EMPTY_ARRAY
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

    /** The cached index only — a highlighting pass must not build it; empty means "no verdict". */
    private fun knownKeys(service: FlowableModelIndexService, site: KeySite): Set<String> {
        val index = service.cachedOrRequest() ?: return emptySet()
        val types = site.targetTypes
        if (types.size == 1) return index.keySetOf(types.first())
        val keys = LinkedHashSet<String>()
        for (type in types) keys.addAll(index.keySetOf(type))
        return keys
    }

}
