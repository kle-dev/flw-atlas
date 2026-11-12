package com.flowable.atlas.inspection

import com.flowable.atlas.completion.FlowableXmlKeyCatalog
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.navigation.FlowableXmlKeyReferenceContributor
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlText

/**
 * Flags a model-XML cross-reference whose key matches no indexed model of the expected type — e.g. a
 * BPMN `callActivity calledElement="MISSING-PROC"` or a `flowable:formKey="MISSING-FORM"` pointing at
 * a key that no model in the project declares. Catches broken deployments before runtime.
 *
 * Only reports when the project actually contains keys of that type (so an unindexed / empty project
 * is never falsely flagged), the value is a plain key (not an `${expression}`) and not one of the keys the
 * platform ships itself — the verdict is [ModelKeyVerdict], shared with the JSON models. Offers a quick fix
 * to the closest known key, on attributes and on element text alike — the text read without the CDATA
 * section Design wraps it in. See [FlowableXmlKeyCatalog].
 *
 * A model under `src/test` is judged like any other: a test process calling a process that does not
 * exist fails at deployment the same way, and the CLI's report judges it too. (The Java inspection
 * skips test *code*, where a literal is broken on purpose to assert the failure.)
 */
class FlowableXmlBrokenKeyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : XmlElementVisitor() {
            override fun visitXmlAttribute(attribute: XmlAttribute) {
                val site = FlowableXmlKeyCatalog.siteForAttribute(attribute) ?: return
                val value = attribute.value ?: return
                val valueElement = attribute.valueElement ?: return
                report(holder, valueElement, null, value, site.types) { ReplaceXmlKeyFix(it) }
            }

            // extension elements carrying a key as TEXT: <flowable:eventType>, <flowable:channelKey>, …
            override fun visitXmlText(text: XmlText) {
                val tag = text.parentTag ?: return
                val site = FlowableXmlKeyCatalog.textSiteForTag(tag) ?: return
                val range = FlowableXmlKeyReferenceContributor.keyRangeIn(text) ?: return
                report(holder, text, range, range.substring(text.text), site.types) { ReplaceXmlTextFix(it) }
            }
        }
    }

    private fun report(
        holder: ProblemsHolder,
        element: PsiElement,
        range: TextRange?,
        value: String,
        types: List<ModelType>,
        fixFor: (String) -> LocalQuickFix,
    ) {
        val verdict = ModelKeyVerdict.unknownKey(element.project, value, types) ?: return
        val fixes = verdict.suggestion?.let { arrayOf(fixFor(it)) } ?: LocalQuickFix.EMPTY_ARRAY
        if (range == null) {
            holder.registerProblem(element, verdict.message, ProblemHighlightType.WARNING, *fixes)
        } else {
            holder.registerProblem(element, range, verdict.message, *fixes)
        }
    }

    /** Replaces the flagged attribute value with a known key. */
    private class ReplaceXmlKeyFix(private val replacement: String) : LocalQuickFix {
        override fun getName(): String = "Replace with '$replacement'"
        override fun getFamilyName(): String = ReplaceStringLiteralFix.KNOWN_MODEL_KEY

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val attribute = PsiTreeUtil.getParentOfType(descriptor.psiElement, XmlAttribute::class.java, false) ?: return
            attribute.setValue(replacement)
        }
    }

    /** Replaces the flagged element text with a known key — the same fix the attribute site always had. */
    private class ReplaceXmlTextFix(private val replacement: String) : LocalQuickFix {
        override fun getName(): String = "Replace with '$replacement'"
        override fun getFamilyName(): String = ReplaceStringLiteralFix.KNOWN_MODEL_KEY

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            (descriptor.psiElement as? XmlText)?.value = replacement
        }
    }
}
