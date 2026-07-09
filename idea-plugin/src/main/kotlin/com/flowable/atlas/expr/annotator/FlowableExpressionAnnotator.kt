package com.flowable.atlas.expr.annotator

import com.flowable.atlas.expr.ExprSeverity
import com.flowable.atlas.expr.ExpressionValidator
import com.flowable.atlas.expr.lang.FlowableExprFile
import com.flowable.atlas.expr.lang.dialectOf
import com.flowable.atlas.settings.FlowableAtlasSettings
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
 * Surfaces **structural syntax errors** as editor annotations (squiggles + error stripe) on any
 * Flowable expression fragment — the standalone playground field and every injected `${…}` / `{{…}}`
 * fragment alike. Runs once per fragment (on the [FlowableExprFile] root).
 *
 * Semantic findings (unknown function/namespace, dialect misuse, grounding) live in the
 * `localInspection`s ([com.flowable.atlas.expr.inspection.FlowableExprUnknownFunctionInspection],
 * [com.flowable.atlas.expr.inspection.FlowableExprGroundingInspection]) so severity, enablement and
 * the project allowlist are user-controllable. Syntax stays here: it is a grammar fact, and the
 * annotator also runs in the playground's LanguageTextField where inspections do not.
 */
class FlowableExpressionAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is FlowableExprFile) return
        if (!FlowableAtlasSettings.getInstance().expressionValidation) return
        val dialect = dialectOf(element.language) ?: return

        for (p in ExpressionValidator.validateSyntax(element.text, dialect)) {
            val severity = if (p.severity == ExprSeverity.ERROR) HighlightSeverity.ERROR else HighlightSeverity.WARNING
            var builder = holder.newAnnotation(severity, p.message)
                .range(TextRange(p.startOffset, p.endOffset))
            p.quickFix?.let { qf ->   // local capture: quickFix is a val from another module (:core), not smart-castable
                builder = builder.withFix(ReplaceExprTextFix(p.startOffset, p.endOffset, qf))
            }
            builder.create()
        }
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
