package com.flowable.atlas.usage

import com.flowable.atlas.FlowableAtlasBundle
import com.flowable.atlas.completion.SiteMatching
import com.flowable.atlas.completion.ValueKeyMatching
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.hint.HintManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import java.util.function.Supplier
import javax.swing.Icon

/**
 * Puts a gutter icon on a Flowable model-**key** string literal (a [SiteMatching.keySiteForLiteral]
 * site such as `startProcessInstanceByKey("onboarding")`) when the resolved model ships a rendered
 * `.svg` next to it (Flowable Design's export layout — see [FlowableDiagram]); clicking it opens that
 * diagram in IntelliJ's built-in image/SVG viewer, so the process/case/form/decision can be seen
 * without opening Flowable Design. When no `.svg` exists (e.g. a deployment BAR) no marker is added —
 * the marker is self-limiting, so it never appears where it would do nothing.
 *
 * Mirrors [FlowableModelReferenceLineMarkerProvider]: the highlight pass does only cheap cached-index
 * lookups (never builds the index) plus a sibling-file check; opening the editor is done on the click.
 */
class FlowableDiagramLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        if (elements.isEmpty()) return
        // cachedOrNull() only — never build the index from a highlighting pass. If it isn't ready yet,
        // kick a background build and show nothing this pass; markers appear once the index exists.
        val service = elements.first().project.service<FlowableModelIndexService>()
        val index = service.cachedOrNull() ?: run {
            ApplicationManager.getApplication().executeOnPooledThread { runCatching { service.index() } }
            return
        }
        val valueBased = ValueKeyMatching.enabled()
        for (element in elements) {
            // Line markers must be anchored on a leaf; a string literal's single child is that leaf.
            val literal = element.parent as? PsiLiteralExpression ?: continue
            if (literal.firstChild !== element) continue
            val key = literal.value as? String ?: continue
            val site = SiteMatching.keySiteForLiteral(literal)
            // Call-site match narrows by the site's target types; otherwise (opt-in) match by value
            // against every model type — the key must still equal a real indexed key.
            val candidates = when {
                site != null -> index.find(key).filter { it.type in site.targetTypes }
                valueBased && ValueKeyMatching.plausible(key) -> index.find(key)
                else -> continue
            }
            val entry = candidates.firstOrNull { FlowableDiagram.hasOpenableDiagram(it.file, it.type) } ?: continue
            result.add(buildMarker(element, entry.file, entry.type))
        }
    }

    private fun buildMarker(anchor: PsiElement, modelFile: VirtualFile, type: ModelType): LineMarkerInfo<PsiElement> =
        LineMarkerInfo(
            anchor,
            anchor.textRange,
            ICON,
            { _ -> TOOLTIP },
            { _, elt -> openDiagram(elt.project, modelFile, type) },
            GutterIconRenderer.Alignment.RIGHT,
            Supplier { TOOLTIP },
        )

    private fun openDiagram(project: Project, modelFile: VirtualFile, type: ModelType) {
        // Resolve the bundled sibling .svg or render one from the model's DI layout; both open in the
        // bundled Images viewer. Opening runs on the EDT (the click thread). A diagram-bearing model
        // that carries no layout at all resolves to null — show a hint instead of an empty tab.
        val svg = DiagramSvgCache.getInstance(project).resolveDiagram(modelFile, type)
        if (svg != null) {
            FileEditorManager.getInstance(project).openFile(svg, true)
        } else {
            FileEditorManager.getInstance(project).selectedTextEditor
                ?.let { HintManager.getInstance().showInformationHint(it, NO_LAYOUT_HINT) }
        }
    }

    private companion object {
        val TOOLTIP: String = FlowableAtlasBundle.message("linemarker.diagram.tooltip")
        val NO_LAYOUT_HINT: String = FlowableAtlasBundle.message("linemarker.diagram.nolayout")
        val ICON: Icon = AllIcons.FileTypes.Image
    }
}
