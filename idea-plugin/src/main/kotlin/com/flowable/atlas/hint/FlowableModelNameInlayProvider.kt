package com.flowable.atlas.hint

import com.flowable.atlas.index.FlowableIndex
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.navigation.ModelFileKeySites
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag

/**
 * Inline hint inside a **model file**: the referenced model's name after the key that names it, so
 * `calledElement="DEMO-P002"` reads as `calledElement="DEMO-P002"‹Order fulfilment›` and a data object's
 * `"referencedServiceDefinitionModelKey": "DEMO-S004"` says which service that is — without hovering,
 * in a minified export as much as in a hand-written one. The sites are the ones Ctrl+click navigates
 * from ([ModelFileKeySites]); a model whose name equals its key adds nothing and gets no hint, and the
 * file's own key gets none either (its name is the next attribute over).
 *
 * Toggle under Settings → Editor → Inlay Hints → Values → "Model names".
 */
class FlowableModelNameInlayProvider : InlayHintsProvider {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        val vFile = file.viewProvider.virtualFile
        if (ModelFiles.typeOf(vFile) == null) return null
        // Never build the (blocking) index from a hint pass. If it isn't ready yet, kick a background
        // build and show nothing this pass; hints appear once the index exists.
        val index = file.project.service<FlowableModelIndexService>().cachedOrRequest() ?: return null
        return Collector(index)
    }

    private class Collector(private val index: FlowableIndex) : SharedBypassCollector {
        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            if (element !is XmlAttributeValue && element !is XmlTag && element !is JsonStringLiteral) return
            val site = ModelFileKeySites.siteOf(element) ?: return
            if (site.declaration) return
            val name = nameOf(index, site) ?: return
            // after the closing quote of a quoted key, right after the text of an element's key
            val offset = if (element is XmlTag) site.range.endOffset else element.textRange.endOffset
            sink.addPresentation(InlineInlayPosition(offset, relatedToPrevious = true), hintFormat = HintFormat.default) { text(name) }
        }
    }

    companion object {
        /** The name of the model [site] names, or null when none is indexed or the name repeats the key. */
        fun nameOf(index: FlowableIndex, site: ModelFileKeySites.Site): String? {
            val entry = index.find(site.key).firstOrNull { it.type in site.types } ?: return null
            return entry.name.takeIf { it.isNotBlank() && it != site.key }
        }
    }
}
