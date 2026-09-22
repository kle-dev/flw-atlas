package com.flowable.atlas.usage

import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProgressIndicator
import com.flowable.atlas.icons.AtlasIcons
import com.flowable.atlas.FlowableAtlasBundle
import com.flowable.atlas.completion.SiteMatching
import com.flowable.atlas.completion.ValueKeyMatching
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.navigation.ModelFileKeySites
import com.flowable.atlas.preview.FlowableModelPreviewEditorProvider
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlText
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.hint.HintManager
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
 * Inside a model file the same mark sits on the file's own key and on every cross-reference to a
 * process, case, decision or form ([ModelFileKeySites]) — the diagram of the process you are reading, one
 * click away, and the callee's from its call activity.
 * The icon appears when the resolved model has an openable diagram (a bundled `.svg` from Flowable
 * Design's export layout, or a DI layout Atlas can render — see [FlowableDiagram]); clicking it opens
 * the model beside its diagram ([FlowableModelPreviewEditorProvider]), so the process/case/decision
 * can be seen without opening Flowable Design; a form or page shows a wireframe of its grid. A model that
 * editor does not take, but that ships a bundled `.svg`, opens that in IntelliJ's image viewer. When there is no
 * diagram (an action, say) no marker is added — the marker is self-limiting, so it never appears where
 * it would do nothing.
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
        val first = elements.first()
        val service = first.project.service<FlowableModelIndexService>()
        val index = service.cachedOrRequest() ?: return
        val valueBased = ValueKeyMatching.enabled()
        val inModelFile = first.containingFile?.virtualFile?.let { ModelFiles.typeOf(it) } != null
        for (element in elements) {
            val (key, types) = (if (inModelFile) fileAnchor(element) else keyAnchor(element)) ?: continue
            // Call-site match narrows by the site's target types; otherwise (opt-in) match by value
            // against every model type — the key must still equal a real indexed key.
            val candidates = when {
                types != null -> index.find(key).filter { it.type in types }
                valueBased && ValueKeyMatching.plausible(key) -> index.find(key)
                else -> continue
            }
            val entry = candidates.firstOrNull { FlowableDiagram.hasOpenableDiagram(it.file, it.type) } ?: continue
            result.add(buildMarker(element, entry.file, entry.type, entry.key))
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
    private fun keyAnchor(element: PsiElement): Pair<String, Collection<ModelType>?>? {
        when (val parent = element.parent) {
            is PsiLiteralExpression -> {
                if (parent.firstChild !== element) return null
                val key = parent.value as? String ?: return null
                return key to SiteMatching.keySiteForLiteral(parent)?.targetTypes
            }
            // A constant / local-variable reference at a key site: `processDefinitionKey(PROCESS_KEY)`.
            // Site-gated only — matching a bare identifier by value would light up every mention of it.
            is PsiReferenceExpression -> {
                if (parent.referenceNameElement !== element) return null
                val (site, value) = SiteMatching.keySiteForArgument(parent) ?: return null
                return value to site.targetTypes
            }
            else -> return null
        }
    }

    /**
     * Inside a model file: the key token of a cross-reference — `calledElement="…"`, a `caseRef`, an
     * `<eventType>`'s text, a form's `processReference` — or of the file's own declaration, so a minified
     * BPMN opens its own diagram from its `<process id>` and every call activity opens the callee's.
     * The leaf is the value token itself (never a quote), so one marker per key.
     */
    private fun fileAnchor(element: PsiElement): Pair<String, Collection<ModelType>?>? {
        val parent = element.parent
        val site = when {
            element is XmlToken && element.tokenType == XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN && parent is XmlAttributeValue ->
                ModelFileKeySites.siteOf(parent)
            element is XmlToken && element.tokenType == XmlTokenType.XML_DATA_CHARACTERS && parent is XmlText ->
                parent.parentTag?.let(ModelFileKeySites::siteOf)
            parent is JsonStringLiteral && parent.firstChild === element -> ModelFileKeySites.siteOf(parent)
            else -> null
        } ?: return null
        return site.key to site.types
    }

    private fun buildMarker(anchor: PsiElement, modelFile: VirtualFile, type: ModelType, key: String): LineMarkerInfo<PsiElement> {
        // Names the type and the key, so the mark on a constant says which model it is about.
        val tooltip = FlowableAtlasBundle.message("linemarker.diagram.tooltip", type.display, key)
        return LineMarkerInfo(
            anchor,
            anchor.textRange,
            ICON,
            { _ -> tooltip },
            { _, elt -> openDiagram(elt.project, modelFile, type) },
            GutterIconRenderer.Alignment.RIGHT,
            Supplier { tooltip },
        )
    }

    private fun openDiagram(project: Project, modelFile: VirtualFile, type: ModelType) {
        // The model's own editor, text and picture side by side, painted in Swing — the IDE's SVG viewer
        // is a JCEF browser, slow under Remote Dev, and opens on the markup rather than the picture.
        if (FlowableModelPreviewEditorProvider.openWithPreview(project, modelFile)) return
        // Resolve the bundled sibling .svg or render one from the model's DI layout; both open in the
        // bundled Images viewer. The render — bytes plus a full DI layout pass on a large process — runs
        // in the background; only the opening is the click thread's. A diagram-bearing model that
        // carries no layout at all resolves to null — show a hint instead of an empty tab.
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Rendering Flowable diagram", true) {
            private var svg: VirtualFile? = null

            override fun run(indicator: ProgressIndicator) {
                svg = DiagramSvgCache.getInstance(project).resolveDiagram(modelFile, type)
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                val file = svg
                if (file != null) {
                    FileEditorManager.getInstance(project).openFile(file, true)
                } else {
                    FileEditorManager.getInstance(project).selectedTextEditor
                        ?.let { HintManager.getInstance().showInformationHint(it, NO_LAYOUT_HINT) }
                }
            }
        })
    }

    private companion object {
        val NO_LAYOUT_HINT: String = FlowableAtlasBundle.message("linemarker.diagram.nolayout")
        val ICON: Icon = AtlasIcons.GutterDiagram
    }
}
