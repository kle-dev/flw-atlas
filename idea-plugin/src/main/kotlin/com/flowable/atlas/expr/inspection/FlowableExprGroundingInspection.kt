package com.flowable.atlas.expr.inspection

import com.flowable.atlas.expr.BackendGrounding
import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.expr.catalog.FlowableExpressionCatalog
import com.flowable.atlas.expr.lang.FlowableExprFile
import com.flowable.atlas.expr.lang.dialectOf
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.inspection.Suggestions
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.components.service
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * Opt-in backend "codebase grounding" (disabled by default, mirroring the old settings checkbox):
 * warns when a root identifier of a backend expression is not a known engine root, an indexed
 * process/case variable, or a referenced Spring bean. Process variables can be set at runtime
 * without ever appearing in a model, so this is a hint, never an error — and runtime-only names
 * can be allowlisted via the quick fix.
 */
class FlowableExprGroundingInspection : LocalInspectionTool() {

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file !is FlowableExprFile) return null
        if (dialectOf(file.language) != ExpressionDialect.BACKEND) return null

        val service = file.project.service<FlowableModelIndexService>()
        val known = HashSet<String>()
        known += FlowableExpressionCatalog.rootNames(ExpressionDialect.BACKEND)
        known += service.variables()
        known += service.index().referencedIdentifiers

        val allowlist = FlowableAtlasProjectSettings.getInstance(file.project)
        val problems = BackendGrounding
            .check(file.text, isKnown = { it in known }, suggest = { Suggestions.closest(it, known) })
            .filterNot { allowlist.isAllowlisted(it) }
        if (problems.isEmpty()) return null

        return problems.map { p ->
            val fixes = ArrayList<LocalQuickFix>(2)
            p.quickFix?.let { fixes += ReplaceExprRangeFix(it) }
            p.subject?.let { fixes += AddToExpressionAllowlistFix(it, p.kind) }
            manager.createProblemDescriptor(
                file,
                TextRange(p.startOffset, p.endOffset),
                p.message,
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                isOnTheFly,
                *fixes.toTypedArray(),
            )
        }.toTypedArray()
    }
}
