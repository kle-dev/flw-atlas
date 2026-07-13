package com.flowable.atlas.inspection

import com.flowable.atlas.completion.FlowableXmlKeyCatalog
import com.flowable.atlas.completion.FlowableXmlKeyCatalog.XmlKeySite
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
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
 * is never falsely flagged) and the value is a plain key (not an `${expression}`). Offers a quick fix
 * to the closest known key. See [FlowableXmlKeyCatalog].
 */
class FlowableXmlBrokenKeyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : XmlElementVisitor() {
            override fun visitXmlAttribute(attribute: XmlAttribute) {
                val site = FlowableXmlKeyCatalog.siteForAttribute(attribute) ?: return
                val value = attribute.value ?: return
                if (!FlowableXmlKeyCatalog.isResolvableKey(value)) return

                val service = attribute.project.service<FlowableModelIndexService>()
                val knownKeys = knownKeys(service, site)
                if (knownKeys.isEmpty()) return          // nothing indexed for this type — don't guess
                if (value in knownKeys) return

                val valueElement = attribute.valueElement ?: return
                val typeLabel = site.types.joinToString("/") { it.display }
                val suggestion = Suggestions.closest(value, knownKeys)
                val fixes = suggestion?.let { arrayOf<LocalQuickFix>(ReplaceXmlKeyFix(it)) } ?: LocalQuickFix.EMPTY_ARRAY
                val hint = suggestion?.let { " — did you mean '$it'?" } ?: ""
                holder.registerProblem(
                    valueElement,
                    "'$value' is not a known $typeLabel key$hint",
                    ProblemHighlightType.WARNING,
                    *fixes,
                )
            }

            // extension elements carrying a key as TEXT: <flowable:eventType>, <flowable:channelKey>, …
            override fun visitXmlText(text: XmlText) {
                val tag = text.parentTag ?: return
                val site = FlowableXmlKeyCatalog.textSiteForTag(tag) ?: return
                val value = text.text.trim()
                if (!FlowableXmlKeyCatalog.isResolvableKey(value)) return

                val service = text.project.service<FlowableModelIndexService>()
                val knownKeys = LinkedHashSet<String>()
                for (type in site.types) service.keysOfType(type).forEach { knownKeys.add(it.key) }
                if (knownKeys.isEmpty()) return          // nothing indexed for this type — don't guess
                if (value in knownKeys) return

                val typeLabel = site.types.joinToString("/") { it.display }
                val suggestion = Suggestions.closest(value, knownKeys)
                val hint = suggestion?.let { " — did you mean '$it'?" } ?: ""
                holder.registerProblem(
                    text,
                    "'$value' is not a known $typeLabel key$hint",
                    ProblemHighlightType.WARNING,
                )
            }
        }
    }

    private fun knownKeys(service: FlowableModelIndexService, site: XmlKeySite): Set<String> {
        val keys = LinkedHashSet<String>()
        for (type in site.types) service.keysOfType(type).forEach { keys.add(it.key) }
        return keys
    }

    /** Replaces the flagged attribute value with a known key. */
    private class ReplaceXmlKeyFix(private val replacement: String) : LocalQuickFix {
        override fun getFamilyName(): String = "Replace with '$replacement'"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val attribute = PsiTreeUtil.getParentOfType(descriptor.psiElement, XmlAttribute::class.java, false) ?: return
            attribute.setValue(replacement)
        }
    }
}
