package com.flowable.atlas.hint

import com.flowable.atlas.index.FlowableIndex
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.settings.FlowableAtlasSettings
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression

/**
 * Inline hint: the **action model's name** shown right after a string literal whose value is a Flowable
 * `.action` key — so `"DEMO-A033"` reads as `"DEMO-A033"‹Create support request›` in a constants class
 * or at a call site, without hovering or navigating. The counterpart of
 * [FlowableDataObjectTableInlayProvider], which shows a data object's physical table the same way; an
 * opaque `DEMO-Annn` key is exactly as unreadable as an opaque table-backed data-object key.
 *
 * Matched by **value** (every literal, not only catalogued API call sites) because that is where these
 * keys actually appear — a generated/hand-written constants class. The name comes straight from the
 * model index ([FlowableIndex.keysOfType]); an action whose name is missing or equal to its key adds
 * nothing and gets no hint. Toggle under Settings → Editor → Inlay Hints → Values → "Action names",
 * or Settings → Tools → Flowable Atlas → Inline Hints ([FlowableAtlasSettings.showActionNameInlay]).
 */
class FlowableActionNameInlayProvider : InlayHintsProvider {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (!FlowableAtlasSettings.getInstance().showActionNameInlay) return null
        val service = file.project.service<FlowableModelIndexService>()
        // Never build the (blocking) index from a hint pass. If it isn't ready yet, kick a background
        // build and show nothing this pass; hints appear once the index exists.
        val index = service.cachedOrNull() ?: run {
            ApplicationManager.getApplication().executeOnPooledThread { runCatching { service.index() } }
            return null
        }
        val names = actionNames(index)
        return if (names.isEmpty()) null else Collector(names)
    }

    private class Collector(private val names: Map<String, String>) : SharedBypassCollector {
        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            if (element !is PsiLiteralExpression) return
            val name = (element.value as? String)?.let { names[it] } ?: return
            sink.addPresentation(
                InlineInlayPosition(element.textRange.endOffset, relatedToPrevious = true),
                // HintFormat.default is the boxed inlay the `hasBackground = true` overload used to mean;
                // that overload is deprecated in favour of passing the format itself.
                hintFormat = HintFormat.default,
            ) { text(name) }
        }
    }

    companion object {
        /**
         * Action key → display name, for every indexed `.action` model worth labelling. A nameless
         * model is indexed with its key as the name (see [FlowableModelIndexService]), so dropping
         * `name == key` also drops the "hint that repeats the literal" case.
         */
        fun actionNames(index: FlowableIndex): Map<String, String> =
            index.keysOfType(ModelType.ACTION)
                .filter { it.name.isNotBlank() && it.name != it.key }
                .associate { it.key to it.name }
    }
}
