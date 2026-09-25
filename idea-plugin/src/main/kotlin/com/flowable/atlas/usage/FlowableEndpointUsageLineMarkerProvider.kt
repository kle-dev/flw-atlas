package com.flowable.atlas.usage

import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.icons.AtlasIcons
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.ui.awt.RelativePoint
import java.awt.event.MouseEvent
import java.util.function.Supplier
import javax.swing.Icon

/**
 * Puts a gutter icon on a Spring REST handler method (`@GetMapping`/`@PostMapping`/…) whose endpoint
 * URL is called from a Flowable model — an HTTP service task, a form/page REST button, a `.service`
 * operation or a REST data source; clicking it navigates to those model files.
 * Mirrors [FlowableBotActionLineMarkerProvider]: the highlight pass does only cheap cached-index
 * lookups (endpoint path vs. the indexed URL set), the (potentially slow) file scan that
 * resolves the exact model files is deferred to the click.
 */
class FlowableEndpointUsageLineMarkerProvider : LineMarkerProviderDescriptor() {

    // A descriptor, not a bare provider: Settings → Editor → General → Gutter Icons lists it by this
    // name with its icon, so a team that finds one kind of mark noisy can switch that one off.
    override fun getName(): String = message("linemarker.endpoint.name")
    override fun getIcon(): Icon = AtlasIcons.GutterEndpoint


    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        if (elements.isEmpty()) return
        // cachedOrNull() only — never build the index from a highlighting pass. If it isn't ready yet,
        // kick a background build and show nothing this pass; markers appear once the index exists.
        // Without the build, a cold index is indistinguishable from "no model calls this endpoint".
        val service = elements.first().project.service<FlowableModelIndexService>()
        val index = service.cachedOrRequest() ?: return
        var all: List<EndpointPsi.Endpoint>? = null
        for (element in elements) {
            if (element !is PsiIdentifier) continue
            val method = element.parent as? PsiMethod ?: continue
            if (method.nameIdentifier !== element) continue
            val mine = EndpointPsi.endpointsOf(method).ifEmpty { continue }
            val everything = all ?: EndpointPsi.projectEndpoints(method.project).also { all = it }
            val called = mine.filter { EndpointModelScan.anyModelCalls(index, it, everything) }
            if (called.isEmpty()) continue
            result.add(buildMarker(element, called))
        }
    }

    private fun buildMarker(identifier: PsiIdentifier, endpoints: List<EndpointPsi.Endpoint>): LineMarkerInfo<PsiElement> {
        val tooltip = message("linemarker.endpoint.tooltip", endpoints.joinToString(", ") { "${it.verb} ${it.path}" })
        return LineMarkerInfo(
            identifier,
            identifier.textRange,
            ICON,
            { _ -> tooltip },
            { event, elt -> navigate(event, elt, endpoints) },
            GutterIconRenderer.Alignment.RIGHT,
            Supplier { tooltip },
        )
    }

    private fun navigate(event: MouseEvent, element: PsiElement, endpoints: List<EndpointPsi.Endpoint>) {
        val project = element.project
        object : Task.Backgroundable(project, message("linemarker.endpoint.progress"), true) {
            override fun run(indicator: ProgressIndicator) {
                val usages = EndpointModelScan.affectedModelUsages(project, endpoints)
                val at = RelativePoint(event)
                ApplicationManager.getApplication().invokeLater {
                    ModelReferenceNavigator.show(project, usages, message("linemarker.endpoint.popup", endpoints.first().let { "${it.verb} ${it.path}" }), at)
                }
            }
        }.queue()
    }

    private companion object {
        val ICON: Icon = AtlasIcons.GutterEndpoint
    }
}
