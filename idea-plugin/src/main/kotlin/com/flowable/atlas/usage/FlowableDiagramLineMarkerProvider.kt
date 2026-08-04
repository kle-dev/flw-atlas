package com.flowable.atlas.usage

import com.flowable.atlas.FlowableAtlasBundle
import com.flowable.atlas.completion.KeySite
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
import com.intellij.psi.PsiReferenceExpression
import java.util.function.Supplier
import javax.swing.Icon

/**
 * Puts a gutter icon on a Flowable model-**key** expression: a string literal at a
 * [SiteMatching.keySiteForLiteral] site such as `startProcessInstanceByKey("onboarding")`, or — the
 * generated model-constants / local-variable pattern — a constant reference at a key site such as
 * `processDefinitionKey(ModelConstants.ONBOARDING)`, whose compile-time value [SiteMatching] resolves.
 * The icon appears when the resolved model has an openable diagram (a bundled `.svg` from Flowable
 * Design's export layout, or a DI layout Atlas can render — see [FlowableDiagram]); clicking it opens
 * that diagram in IntelliJ's built-in image/SVG viewer, so the process/case/decision can be seen
 * without opening Flowable Design. When there is no diagram (e.g. a deployment BAR of a form) no
 * marker is added — the marker is self-limiting, so it never appears where it would do nothing.
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
            val (key, site) = keyAnchor(element) ?: continue
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

    /**
     * The model key [element] carries plus the call site it sits at (null when there is no site — the
     * value-based path), or null when the leaf is not a key anchor at all.
     *
     * Line markers must be anchored on a **leaf**, so both branches key off the leaf and look up:
     * a literal's single child, and a reference's name identifier. A qualified constant
     * (`ModelConstants.ONBOARDING`) therefore yields exactly one marker — the qualifier's own
     * reference expression is not an argument, so it resolves to no site.
     */
    private fun keyAnchor(element: PsiElement): Pair<String, KeySite?>? {
        when (val parent = element.parent) {
            is PsiLiteralExpression -> {
                if (parent.firstChild !== element) return null
                val key = parent.value as? String ?: return null
                return key to SiteMatching.keySiteForLiteral(parent)
            }
            // A constant / local-variable reference at a key site: `processDefinitionKey(PROCESS_KEY)`.
            // Site-gated only — matching a bare identifier by value would light up every mention of it.
            is PsiReferenceExpression -> {
                if (parent.referenceNameElement !== element) return null
                val (site, value) = SiteMatching.keySiteForArgument(parent) ?: return null
                return value to site
            }
            else -> return null
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
