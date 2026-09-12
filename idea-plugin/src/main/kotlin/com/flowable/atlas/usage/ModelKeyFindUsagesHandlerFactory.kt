package com.flowable.atlas.usage

import com.flowable.atlas.completion.SiteMatching
import com.flowable.atlas.completion.ValueKeyMatching
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelKeyDeclaration
import com.flowable.atlas.model.ModelType
import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor

/**
 * Find Usages on a model's **own key** — the `id` of a process, case or decision, the `"key"` of a JSON
 * model — answers "which processes call this sub-process, which pages open this form, which Java starts
 * it". The other direction (Java → models) has existed for a while; a key selected in a model file used
 * to answer "no usages".
 *
 * Model usages are found in text: a key is referenced as a quoted value (`calledElement="X"`, `"formKey":
 * "X"`) or as an extension element's text (`<flowable:eventType>X</flowable:eventType>`), and the scan
 * runs over every model file and archive entry in the same scope the index uses. Java usages are the key
 * literals at Flowable API sites (and, when switched on, any literal equal to the key), found through the
 * word index.
 */
class ModelKeyFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    /** The declared key an element belongs to: `(type, key, the value element)`. */
    data class Declaration(val type: ModelType, val key: String, val element: PsiElement)

    override fun canFindUsages(element: PsiElement): Boolean = declarationOf(element) != null

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler? {
        val decl = declarationOf(element) ?: return null
        return object : FindUsagesHandler(decl.element) {
            override fun processElementUsages(element: PsiElement, processor: Processor<in UsageInfo>, options: FindUsagesOptions): Boolean {
                report(decl, processor)
                return true
            }
        }
    }

    private fun report(decl: Declaration, processor: Processor<in UsageInfo>) {
        val project = decl.element.project
        ReadAction.run<RuntimeException> {
            if (project.isDisposed) return@run
            val psiManager = PsiManager.getInstance(project)
            val declFile = decl.element.containingFile?.virtualFile
            val declOffset = decl.element.containingFile?.text?.let { ModelKeyDeclaration.offsetOf(it, decl.type, decl.key) }
            ModelReferenceScan.forEachModelText(project) { vf, text ->
                val ranges = keyReferenceRanges(text, decl.key)
                if (ranges.isEmpty()) return@forEachModelText
                val psiFile = psiManager.findFile(vf) ?: return@forEachModelText
                for (r in ranges) {
                    if (vf == declFile && declOffset != null && r.first == declOffset) continue   // the declaration itself
                    processor.process(UsageInfo(psiFile, r.first, r.last + 1, false))
                }
            }
            // Java: the literals at a Flowable API site naming this key — or any literal, when that is on.
            PsiSearchHelper.getInstance(project).processElementsWithWord(
                { el, _ ->
                    val literal = el as? PsiLiteralExpression ?: return@processElementsWithWord true
                    if (literal.value != decl.key) return@processElementsWithWord true
                    val site = SiteMatching.keySiteForLiteral(literal)
                    val hit = if (site != null) decl.type in site.targetTypes else ValueKeyMatching.enabled()
                    if (hit) processor.process(UsageInfo(literal))
                    true
                },
                GlobalSearchScope.projectScope(project), decl.key, UsageSearchContext.IN_STRINGS, true,
            )
        }
    }

    companion object {
        private val XML_MODEL_ELEMENTS = mapOf("process" to ModelType.PROCESS, "case" to ModelType.CASE, "decision" to ModelType.DECISION)

        /** What [element] declares, walking up from a leaf to the attribute value or JSON string that is the key. */
        fun declarationOf(element: PsiElement): Declaration? {
            val vf: VirtualFile = element.containingFile?.virtualFile ?: return null
            val type = ModelFiles.typeOf(vf) ?: return null
            val value = PsiTreeUtil.getParentOfType(element, XmlAttributeValue::class.java, false)
            if (value != null) {
                val attr = value.parent as? XmlAttribute ?: return null
                if (attr.localName != "id") return null
                val elementType = XML_MODEL_ELEMENTS[attr.parent?.localName] ?: return null
                if (elementType != type) return null
                val key = value.value.takeIf { it.isNotBlank() } ?: return null
                return Declaration(type, key, value)
            }
            val str = PsiTreeUtil.getParentOfType(element, JsonStringLiteral::class.java, false) ?: return null
            val prop = str.parent as? JsonProperty ?: return null
            if (prop.name != "key" || prop.value != str) return null
            // the model's key: at the root, or under `metadata` — never a `key` nested deeper (a column's, a field's)
            val holder = prop.parent as? JsonObject ?: return null
            val atRoot = holder.parent?.parent == null || holder.parent is com.intellij.json.psi.JsonFile
            val underMetadata = (holder.parent as? JsonProperty)?.let { it.name == "metadata" && it.parent?.parent is com.intellij.json.psi.JsonFile } == true
            if (!atRoot && !underMetadata) return null
            return Declaration(type, str.value, str)
        }

        /** Every occurrence of [key] as a quoted value or as an element's whole text, as ranges into [text]. */
        fun keyReferenceRanges(text: String, key: String): List<IntRange> {
            val out = ArrayList<IntRange>()
            for (wrap in listOf("\"$key\"", "'$key'", ">$key<")) {
                var i = text.indexOf(wrap)
                while (i >= 0) { out.add(i + 1 until i + 1 + key.length); i = text.indexOf(wrap, i + 1) }
            }
            return out.sortedBy { it.first }
        }
    }
}
