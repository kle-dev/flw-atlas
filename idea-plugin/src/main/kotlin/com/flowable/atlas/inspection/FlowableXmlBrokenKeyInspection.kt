package com.flowable.atlas.inspection

import com.flowable.atlas.completion.FlowableXmlKeyCatalog
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.index.ProjectModelScope
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.navigation.FlowableXmlKeyReferenceContributor
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
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
 * is never falsely flagged) and the value is a plain key (not an `${expression}`). Offers a quick fix
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
        if (!FlowableXmlKeyCatalog.isResolvableKey(value)) return
        val service = element.project.service<FlowableModelIndexService>()
        val knownKeys = knownKeys(service, types)
        if (knownKeys.isEmpty()) return          // nothing indexed for this type — don't guess
        if (value in knownKeys) return

        val typeLabel = types.joinToString("/") { it.display }
        val suggestion = Suggestions.closest(value, knownKeys)
        val fixes = suggestion?.let { arrayOf(fixFor(it)) } ?: LocalQuickFix.EMPTY_ARRAY
        val hint = suggestion?.let { " — did you mean '$it'?" } ?: ""
        // In a monorepo the index is one sub-project's; a key from another module is unknown *here*,
        // and the message has to say so or it reads as "this key does not exist".
        val scope = ProjectModelScope.label(element.project)?.let { " in $it" } ?: ""
        val message = "'$value' is not a known $typeLabel key$scope$hint"
        if (range == null) {
            holder.registerProblem(element, message, ProblemHighlightType.WARNING, *fixes)
        } else {
            holder.registerProblem(element, range, message, *fixes)
        }
    }

    /** The cached index only — a highlighting pass must not build it; empty means "no verdict". */
    private fun knownKeys(service: FlowableModelIndexService, types: Collection<ModelType>): Set<String> {
        val index = service.cachedOrRequest() ?: return emptySet()
        if (types.size == 1) return index.keySetOf(types.first())
        val keys = LinkedHashSet<String>()
        for (type in types) keys.addAll(index.keySetOf(type))
        return keys
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
