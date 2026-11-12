package com.flowable.atlas.inspection

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiUtil

/**
 * The fix for an unknown key behind a **constant** (`caseDefinitionKey(ModelConstants.REVIEW)`): change the
 * constant's initializer, in the file that declares it. A literal at the same site got *Replace with '…'*
 * while the constant got the same warning and nothing to do about it, though the generated constants class
 * is exactly the pattern the plugin encourages.
 *
 * Offered only when the constant resolves to a writable String literal — [initializerLiteral] is checked
 * at registration, so the fix never appears and then does nothing.
 */
class ChangeConstantValueFix(
    private val replacement: String,
    private val constantName: String,
    private val fileName: String,
) : LocalQuickFix {

    override fun getName(): String = "Change constant value to '$replacement'"
    override fun getFamilyName(): String = "Change the constant to a known model key"

    // The edit lands in another file, so the default preview (a diff of *this* file) would be empty.
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
        IntentionPreviewInfo.Html(
            "Changes the initializer of <code>$constantName</code> in <code>$fileName</code> to <code>\"$replacement\"</code>.",
        )

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val ref = descriptor.psiElement as? PsiReferenceExpression ?: return
        val literal = initializerLiteral(ref) ?: return
        literal.replace(JavaPsiFacade.getElementFactory(project).createExpressionFromText("\"$replacement\"", literal))
    }

    companion object {
        /** The writable String literal that initializes the variable [ref] resolves to, or null. */
        fun initializerLiteral(ref: PsiReferenceExpression): PsiLiteralExpression? {
            val variable = ref.resolve() as? PsiVariable ?: return null
            val literal = PsiUtil.skipParenthesizedExprDown(variable.initializer) as? PsiLiteralExpression ?: return null
            if (literal.value !is String) return null
            if (literal.containingFile?.isWritable != true) return null
            return literal
        }
    }
}
