package com.flowable.atlas.usage

import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.icons.AtlasIcons
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
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
        val service = elements.first().project.service<FlowableModelIndexService>()
        val index = service.cachedOrRequest() ?: return
        for (element in elements) {
            if (element !is PsiIdentifier) continue
            val cls = element.parent as? PsiClass ?: continue
            if (cls.nameIdentifier !== element) continue
            val botKey = BotPsi.botKeyOf(cls) ?: continue
            val actions = index.actionsUsingBot(botKey).size
            if (actions == 0) continue
            result.add(buildMarker(element, botKey, actions))
        }
    }

    private fun buildMarker(identifier: PsiIdentifier, botKey: String, actions: Int): LineMarkerInfo<PsiElement> {
        // The count is free here — the highlight pass already looked the actions up.
        val tooltip = message("linemarker.bot.tooltip", actions)
        return LineMarkerInfo(
            identifier,
            identifier.textRange,
            ICON,
            { _ -> tooltip },
            { event, elt -> navigate(event, elt, botKey) },
            GutterIconRenderer.Alignment.RIGHT,
            Supplier { tooltip },
        )
    }

    private fun navigate(event: MouseEvent, element: PsiElement, botKey: String) {
        val project = element.project
        object : Task.Backgroundable(project, message("linemarker.bot.progress"), true) {
            override fun run(indicator: ProgressIndicator) {
                // each action opens at its `botKey` — the line that names this bot, not the top of the file
                val usages = project.service<FlowableModelIndexService>().actionsUsingBot(botKey)
                    .map { it.file }.distinct()
                    .associateWith { file -> botKeyOffset(file) }
                val at = RelativePoint(event)
                ApplicationManager.getApplication().invokeLater {
                    ModelReferenceNavigator.show(project, usages, message("linemarker.bot.popup", botKey), at)
                }
            }
        }.queue()
    }

    /** Offset of the `botKey` value in an action file, or null when the text does not spell it that way. */
    private fun botKeyOffset(file: VirtualFile): Int? {
        val text = runCatching { String(file.contentsToByteArray(), Charsets.UTF_8) }.getOrNull() ?: return null
        return BOT_KEY_VALUE.find(text)?.groups?.get(1)?.range?.first
    }

    private companion object {
        val BOT_KEY_VALUE = Regex("\"botKey\"\\s*:\\s*\"([^\"]*)\"")
        val ICON: Icon = AtlasIcons.GutterBot
    }
}
