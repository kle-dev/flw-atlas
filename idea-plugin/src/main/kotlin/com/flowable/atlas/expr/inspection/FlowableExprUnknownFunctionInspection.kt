package com.flowable.atlas.expr.inspection

import com.flowable.atlas.expr.ExprProblem
import com.flowable.atlas.expr.ExprProblemKind
import com.flowable.atlas.expr.ExpressionValidator
import com.flowable.atlas.expr.catalog.FlowableCustomFunctions
import com.flowable.atlas.expr.lang.FlowableExprFile
import com.flowable.atlas.expr.lang.dialectOf
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * Semantic expression findings — unknown function/namespace, unknown `flw.*` member, dialect misuse —
 * as a real inspection: severity, enablement and scope live in the inspection profile
 * (Settings → Editor → Inspections → Flowable), and findings about project-provided functions can be
 * allowlisted per project via the quick fix (the catalog is hand-maintained and cannot know them).
 *
 * Registered without a `language` attribute (the two dialect languages share no registered base
 * language); the `file !is FlowableExprFile` bail-out keeps it O(1) on every other file. Structural
 * syntax errors stay in the annotator — they are grammar facts, not configuration.
 */
class FlowableExprUnknownFunctionInspection : LocalInspectionTool() {

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file !is FlowableExprFile) return null
        val dialect = dialectOf(file.language) ?: return null
        val allowlist = FlowableAtlasProjectSettings.getInstance(file.project)
        val custom = FlowableCustomFunctions.getInstance(file.project).catalog()
        val problems = ExpressionValidator.validateSemantics(file.text, dialect, custom)
            .filterNot { allowlist.isAllowlisted(it) }
        if (problems.isEmpty()) return null
        return problems.map { toDescriptor(it, file, manager, isOnTheFly) }.toTypedArray()
    }

    private fun toDescriptor(
        p: ExprProblem,
        file: FlowableExprFile,
        manager: InspectionManager,
        isOnTheFly: Boolean,
    ): ProblemDescriptor {
        val fixes = ArrayList<LocalQuickFix>(2)
        p.quickFix?.let { fixes += ReplaceExprRangeFix(it) }
        if (p.subject != null && p.kind != ExprProblemKind.DIALECT_MISUSE) {
            fixes += AddToExpressionAllowlistFix(p.subject, p.kind)
        }
        return manager.createProblemDescriptor(
            file,
            TextRange(p.startOffset, p.endOffset),
            p.message,
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            isOnTheFly,
            *fixes.toTypedArray(),
        )
    }
}
