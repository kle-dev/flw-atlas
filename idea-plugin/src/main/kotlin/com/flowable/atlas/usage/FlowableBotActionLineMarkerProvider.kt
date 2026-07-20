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
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.ui.awt.RelativePoint
import java.awt.event.MouseEvent
import java.util.function.Supplier
import javax.swing.Icon

/**
 * Puts a gutter icon on a Flowable **bot** class (a `BotService` implementor); clicking it navigates
 * to the `.action` models that invoke the bot (matched by the bot's `getKey()` against each action's
 * `botKey`). Mirrors [FlowableModelReferenceLineMarkerProvider]: the highlight pass does only cheap
 * cached-index lookups, the (potentially slow) resolution of action files is deferred to the click.
 */
class FlowableBotActionLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        if (elements.isEmpty()) return
        val index = elements.first().project.service<FlowableModelIndexService>().cachedOrNull() ?: return
        for (element in elements) {
            if (element !is PsiIdentifier) continue
            val cls = element.parent as? PsiClass ?: continue
            if (cls.nameIdentifier !== element) continue
            val botKey = BotPsi.botKeyOf(cls) ?: continue
            if (index.actionsUsingBot(botKey).isEmpty()) continue
            result.add(buildMarker(element, botKey))
        }
    }

    private fun buildMarker(identifier: PsiIdentifier, botKey: String): LineMarkerInfo<PsiElement> =
        LineMarkerInfo(
            identifier,
            identifier.textRange,
            ICON,
            { _ -> TOOLTIP },
            { event, elt -> navigate(event, elt, botKey) },
            GutterIconRenderer.Alignment.RIGHT,
            Supplier { TOOLTIP },
        )

    private fun navigate(event: MouseEvent, element: PsiElement, botKey: String) {
        val project = element.project
        object : Task.Backgroundable(project, "Finding actions using this bot", true) {
            override fun run(indicator: ProgressIndicator) {
                val files = project.service<FlowableModelIndexService>().actionsUsingBot(botKey).map { it.file }
                val at = RelativePoint(event)
                ApplicationManager.getApplication().invokeLater {
                    ModelReferenceNavigator.show(project, files, "Actions using this bot", at)
                }
            }
        }.queue()
    }

    private companion object {
        const val TOOLTIP = "Used by Flowable actions"
        val ICON: Icon = IconLoader.getIcon("/META-INF/atlas-hub.svg", FlowableBotActionLineMarkerProvider::class.java)
    }
}
