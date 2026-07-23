package com.flowable.atlas.hint

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.settings.FlowableAtlasSettings
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
 * Inline hint: the physical table name shown right after a string literal whose value is a Flowable
 * **data object** key — so `"kyc-customer"` reads as `"kyc-customer"‹CMM_CUSTOMER›` without hovering
 * or navigating. The key→table map is resolved via the data object's backing `database` service model
 * and cached ([FlowableModelIndexService.dataObjectTables]). Toggle under
 * Settings → Editor → Inlay Hints → Values → "Data object table names", or Settings → Tools →
 * Flowable Atlas → Inline Hints ([FlowableAtlasSettings.showDataObjectTableInlay]) — the hover/Ctrl-Q
 * documentation always shows the table regardless of either toggle.
 */
class FlowableDataObjectTableInlayProvider : InlayHintsProvider {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (!FlowableAtlasSettings.getInstance().showDataObjectTableInlay) return null
        val service = file.project.service<FlowableModelIndexService>()
        // Never build the (blocking) index from a hint pass. If it isn't ready yet, kick a background
        // build and show nothing this pass; hints appear once the index exists.
        if (service.cachedOrNull() == null) {
            ApplicationManager.getApplication().executeOnPooledThread { runCatching { service.index() } }
            return null
        }
        val tables = service.dataObjectTables()
        return if (tables.isEmpty()) null else Collector(tables)
    }

    private class Collector(private val tables: Map<String, String>) : SharedBypassCollector {
        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            if (element !is PsiLiteralExpression) return
            val table = (element.value as? String)?.let { tables[it] } ?: return
            sink.addPresentation(
                InlineInlayPosition(element.textRange.endOffset, relatedToPrevious = true),
                hasBackground = true,
            ) { text(table) }
        }
    }
}
