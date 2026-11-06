package com.flowable.atlas.inspection

import com.flowable.atlas.completion.FlowableJsonKeyCatalog
import com.flowable.atlas.model.ModelFiles
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonElementVisitor
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElementVisitor

/**
 * Flags a model key inside a Flowable **JSON** model that matches no indexed model of the expected type —
 * a form component's subform (`extraSettings.formRef`), a data object's backing service, an app's
 * `childModels`, a channel's event. The sites are [FlowableJsonKeyCatalog]'s, which is what Ctrl+click
 * follows and what the CLI records as references; an unresolved one is the report's `missingRefs` defect.
 *
 * Generated or hand-edited JSON models are where such a key goes wrong — the XML models had this check from
 * the start, and a JSON model with a broken subform deployed without a word in the editor. The verdict is
 * [ModelKeyVerdict], the XML inspection's: no verdict on a `{{binding}}`, on a key the platform ships, or
 * while the project holds no model of the expected type.
 */
class FlowableJsonBrokenKeyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        // Registered for every JSON file; a package.json or a tsconfig has no model keys to walk to.
        val vFile = holder.file.viewProvider.virtualFile
        if (ModelFiles.typeOf(vFile) == null) return PsiElementVisitor.EMPTY_VISITOR
        return object : JsonElementVisitor() {
            override fun visitStringLiteral(literal: JsonStringLiteral) {
                val site = FlowableJsonKeyCatalog.siteForLiteral(literal) ?: return
                val verdict = ModelKeyVerdict.unknownKey(literal.project, literal.value, site.types) ?: return
                val fixes = verdict.suggestion?.let { arrayOf<LocalQuickFix>(ReplaceJsonKeyFix(it)) } ?: LocalQuickFix.EMPTY_ARRAY
                holder.registerProblem(
                    literal, ElementManipulators.getValueTextRange(literal), verdict.message, *fixes,
                )
            }
        }
    }

    /** Replaces the flagged string with a known key — the XML inspection's fix, in JSON. */
    private class ReplaceJsonKeyFix(private val replacement: String) : LocalQuickFix {
        override fun getName(): String = "Replace with '$replacement'"
        override fun getFamilyName(): String = ReplaceStringLiteralFix.KNOWN_MODEL_KEY

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val literal = descriptor.psiElement as? JsonStringLiteral ?: return
            // the content only: replacing the literal reformats around it, and a fix must not touch layout
            ElementManipulators.handleContentChange(literal, replacement)
        }
    }
}
