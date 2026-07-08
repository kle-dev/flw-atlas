package com.flowable.atlas.expr.inspection

import com.flowable.atlas.expr.ExprProblemKind
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager

/**
 * Replaces the flagged range with the suggested text — the "did you mean" fix, as a [LocalQuickFix]
 * (the inspection counterpart of the annotator's IntentionAction fix). Works on injected fragments
 * because the injected document is a `DocumentWindow` that maps edits back to the host file.
 */
class ReplaceExprRangeFix(private val replacement: String) : LocalQuickFix {
    override fun getName(): String = "Replace with '$replacement'"
    override fun getFamilyName(): String = "Flowable expression"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement?.containingFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val range = descriptor.textRangeInElement ?: return
        if (range.endOffset <= document.textLength) {
            document.replaceString(range.startOffset, range.endOffset, replacement)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }
}

/**
 * Records the flagged namespace/function/root in [FlowableAtlasProjectSettings] so the finding (and
 * every future finding about the same subject) disappears project-wide. This is the targeted
 * suppression mechanism for project-provided functions the catalog cannot know.
 */
class AddToExpressionAllowlistFix(
    private val subject: String,
    private val kind: ExprProblemKind,
) : LocalQuickFix {

    override fun getName(): String {
        val what = when (kind) {
            ExprProblemKind.UNKNOWN_NAMESPACE -> "namespace"
            ExprProblemKind.UNKNOWN_ROOT -> "root"
            else -> "function"
        }
        return "Add $what '$subject' to Flowable expression allowlist"
    }

    override fun getFamilyName(): String = "Flowable expression allowlist"

    override fun startInWriteAction(): Boolean = false

    // The fix mutates settings, not the file: the default preview would apply it for real against a
    // non-physical copy and render an empty diff. Explain instead.
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
        IntentionPreviewInfo.Html(
            "Adds <code>$subject</code> to the project's expression allowlist " +
                "(<code>.idea/flowableAtlas.xml</code>) so it is no longer reported.",
        )

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        FlowableAtlasProjectSettings.getInstance(project).allow(subject, kind)
        DaemonCodeAnalyzer.getInstance(project).restart()
    }
}
