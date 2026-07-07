package com.flowable.atlas.expr.annotator

import com.flowable.atlas.expr.BackendGrounding
import com.flowable.atlas.expr.ExprProblem
import com.flowable.atlas.expr.ExprSeverity
import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.expr.ExpressionValidator
import com.flowable.atlas.expr.catalog.FlowableExpressionCatalog
import com.flowable.atlas.expr.lang.FlowableExprFile
import com.flowable.atlas.expr.lang.dialectOf
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.inspection.Suggestions
import com.flowable.atlas.settings.FlowableAtlasSettings
import com.intellij.openapi.components.service
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Surfaces [ExpressionValidator] findings as editor annotations (squiggles + error stripe) on any
 * Flowable expression fragment — the standalone playground field and every injected `${…}` / `{{…}}`
 * fragment alike. Runs once per fragment (on the [FlowableExprFile] root). Unknown-function findings
 * carry a "did you mean …?" quick fix.
 */
class FlowableExpressionAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is FlowableExprFile) return
        val settings = FlowableAtlasSettings.getInstance()
        if (!settings.expressionValidation) return
        val dialect = dialectOf(element.language) ?: return

        val problems = ArrayList(ExpressionValidator.validate(element.text, dialect))
        if (dialect == ExpressionDialect.BACKEND && settings.backendCodebaseGrounding) {
            problems += groundBackend(element, element.text)
        }

        for (p in problems) {
            val severity = if (p.severity == ExprSeverity.ERROR) HighlightSeverity.ERROR else HighlightSeverity.WARNING
            var builder = holder.newAnnotation(severity, p.message)
                .range(TextRange(p.startOffset, p.endOffset))
            if (p.quickFix != null) {
                builder = builder.withFix(ReplaceExprTextFix(p.startOffset, p.endOffset, p.quickFix))
            }
            builder.create()
        }
    }

    /**
     * Opt-in codebase grounding: warn on root identifiers that are not a known engine root, an indexed
     * process/case variable, or a referenced Spring bean. Uses the project-wide variable union (rather
     * than a single model's) to keep false positives low.
     */
    private fun groundBackend(element: PsiElement, body: String): List<ExprProblem> {
        val service = element.project.service<FlowableModelIndexService>()
        val known = HashSet<String>()
        known += FlowableExpressionCatalog.rootNames(ExpressionDialect.BACKEND)
        known += service.variables()
        known += service.index().referencedIdentifiers
        return BackendGrounding.check(body, isKnown = { it in known }, suggest = { Suggestions.closest(it, known) })
    }

    /** Replaces the flagged range with the suggested text (the "did you mean" fix). */
    private class ReplaceExprTextFix(
        private val startOffset: Int,
        private val endOffset: Int,
        private val replacement: String,
    ) : IntentionAction {
        override fun getText(): String = "Replace with '$replacement'"
        override fun getFamilyName(): String = "Flowable expression"
        override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true
        override fun startInWriteAction(): Boolean = true

        override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
            file ?: return
            val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
            if (endOffset <= document.textLength) {
                document.replaceString(startOffset, endOffset, replacement)
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
        }
    }
}
