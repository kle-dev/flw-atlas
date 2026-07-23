package com.flowable.atlas.usage

import com.flowable.atlas.FlowableAtlasBundle
import com.flowable.atlas.completion.SiteMatching
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
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
        // cachedOrNull() only — never build the index from a highlighting pass.
        val index = elements.first().project.service<FlowableModelIndexService>().cachedOrNull() ?: return
        for (element in elements) {
            // Line markers must be anchored on a leaf; a string literal's single child is that leaf.
            val literal = element.parent as? PsiLiteralExpression ?: continue
            if (literal.firstChild !== element) continue
            val site = SiteMatching.keySiteForLiteral(literal) ?: continue
            val key = literal.value as? String ?: continue
            val svg = index.find(key)
                .filter { it.type in site.targetTypes }
                .firstNotNullOfOrNull { FlowableDiagram.siblingSvg(it.file) }
                ?: continue
            result.add(buildMarker(element, svg))
        }
    }

    private fun buildMarker(anchor: PsiElement, svg: VirtualFile): LineMarkerInfo<PsiElement> =
        LineMarkerInfo(
            anchor,
            anchor.textRange,
            ICON,
            { _ -> TOOLTIP },
            { _, elt -> openDiagram(elt.project, svg) },
            GutterIconRenderer.Alignment.RIGHT,
            Supplier { TOOLTIP },
        )

    private fun openDiagram(project: Project, svg: VirtualFile) {
        // The bundled Images plugin renders .svg; opening runs on the EDT (the click thread) — no scan.
        FileEditorManager.getInstance(project).openFile(svg, true)
    }

    private companion object {
        val TOOLTIP: String = FlowableAtlasBundle.message("linemarker.diagram.tooltip")
        val ICON: Icon = AllIcons.FileTypes.Image
    }
}
