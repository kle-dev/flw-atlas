package com.flowable.atlas.usage

import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.icons.AtlasIcons
import com.flowable.atlas.index.FlowableIndex
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.ui.awt.RelativePoint
import java.awt.event.MouseEvent
import java.util.function.Supplier
import javax.swing.Icon

/**
 * Puts a gutter icon on Java methods and bean/delegate classes that are referenced from Flowable
 * models (`${bean.method()}`, `delegateExpression`, `class`, …); clicking it navigates to the model
 * files that use the symbol. The reference set comes from the (already cached) model index — the
 * highlight pass does only O(1) set lookups; the (potentially slow) file scan is deferred to the click.
 */
class FlowableModelReferenceLineMarkerProvider : LineMarkerProvider {

    // The fast per-element pass contributes nothing; everything is done in the slow batch pass so the
    // index lookups never delay the first highlighting pass.
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        if (elements.isEmpty()) return
        // cachedOrNull() only — never build the index from a highlighting pass. Markers appear once the
        // index exists (it is built lazily by completion / implicit-usage / Rebuild).
        val service = elements.first().project.service<FlowableModelIndexService>()
        val index = service.cachedOrRequest() ?: return
        for (element in elements) {
            if (element !is PsiIdentifier) continue
            val names = referencedNames(element, index) ?: continue
            result.add(buildMarker(element, names))
        }
    }

    /** Names the (leaf) [identifier]'s method/class is referenced by from a model, or null when it isn't. */
    private fun referencedNames(identifier: PsiIdentifier, index: FlowableIndex): Set<String>? {
        val names = when (val parent = identifier.parent) {
            is PsiMethod -> if (parent.nameIdentifier === identifier) ModelReferenceScan.namesOf(parent) else return null
            is PsiClass -> if (parent.nameIdentifier === identifier) ModelReferenceScan.namesOf(parent) else return null
            else -> return null
        }
        val referenced = names.any { it in index.referencedIdentifiers || it in index.referencedClassFqns }
        return if (referenced) names else null
    }

    private fun buildMarker(identifier: PsiIdentifier, names: Set<String>): LineMarkerInfo<PsiElement> =
        LineMarkerInfo(
            identifier,
            identifier.textRange,
            ICON,
            { _ -> TOOLTIP },
            { event, elt -> navigate(event, elt, names) },
            GutterIconRenderer.Alignment.RIGHT,
            Supplier { TOOLTIP },
        )

    private fun navigate(event: MouseEvent, element: PsiElement, names: Set<String>) {
        val project = element.project
        object : Task.Backgroundable(project, message("linemarker.reference.progress"), true) {
            override fun run(indicator: ProgressIndicator) {
                val files = ModelReferenceScan.affectedModelFiles(project, names)
                val at = RelativePoint(event)
                ApplicationManager.getApplication().invokeLater {
                    ModelReferenceNavigator.show(project, files, message("linemarker.reference.popup", symbolName(element)), at)
                }
            }
        }.queue()
    }

    /** The identifier's text — what the popup title names. */
    private fun symbolName(element: PsiElement): String = element.text

    companion object {
        private val TOOLTIP: String = message("linemarker.reference.tooltip")
        private val ICON: Icon = AtlasIcons.GutterReference
    }
}
