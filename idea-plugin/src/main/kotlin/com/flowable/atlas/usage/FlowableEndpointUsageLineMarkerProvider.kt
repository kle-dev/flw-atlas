package com.flowable.atlas.usage

import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.ui.awt.RelativePoint
import java.awt.event.MouseEvent
import java.util.function.Supplier
import javax.swing.Icon

/**
 * Puts a gutter icon on a Spring REST handler method (`@GetMapping`/`@PostMapping`/…) whose endpoint
 * URL is called from a Flowable model's HTTP service task; clicking it navigates to those model files.
 * Mirrors [FlowableBotActionLineMarkerProvider]: the highlight pass does only cheap cached-index
 * lookups (endpoint path vs. the indexed `requestUrl` set), the (potentially slow) file scan that
 * resolves the exact model files is deferred to the click.
 */
class FlowableEndpointUsageLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        if (elements.isEmpty()) return
        val index = elements.first().project.service<FlowableModelIndexService>().cachedOrNull() ?: return
        for (element in elements) {
            if (element !is PsiIdentifier) continue
            val method = element.parent as? PsiMethod ?: continue
            if (method.nameIdentifier !== element) continue
            val endpoint = EndpointPsi.endpointOf(method) ?: continue
            if (!EndpointModelScan.anyModelCalls(index, endpoint.path)) continue
            result.add(buildMarker(element, endpoint))
        }
    }

    private fun buildMarker(identifier: PsiIdentifier, endpoint: EndpointPsi.Endpoint): LineMarkerInfo<PsiElement> {
        val tooltip = "Called by Flowable models (${endpoint.verb} ${endpoint.path})"
        return LineMarkerInfo(
            identifier,
            identifier.textRange,
            ICON,
            { _ -> tooltip },
            { event, elt -> navigate(event, elt, endpoint.path) },
            GutterIconRenderer.Alignment.RIGHT,
            Supplier { tooltip },
        )
    }

    private fun navigate(event: MouseEvent, element: PsiElement, path: String) {
        val project = element.project
        object : Task.Backgroundable(project, "Finding models calling this endpoint", true) {
            override fun run(indicator: ProgressIndicator) {
                val files = EndpointModelScan.affectedModelFiles(project, path)
                val at = RelativePoint(event)
                ApplicationManager.getApplication().invokeLater {
                    ModelReferenceNavigator.show(project, files, "Models calling this endpoint", at)
                }
            }
        }.queue()
    }

    private companion object {
        val ICON: Icon =
            IconLoader.getIcon("/META-INF/atlas-hub.svg", FlowableEndpointUsageLineMarkerProvider::class.java)
    }
}
