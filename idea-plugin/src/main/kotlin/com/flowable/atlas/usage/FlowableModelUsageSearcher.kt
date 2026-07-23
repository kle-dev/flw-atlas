package com.flowable.atlas.usage

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.parsing.ModelUsageLocator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.find.findUsages.CustomUsageSearcher
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.util.Processor

/**
 * Reports Flowable model files as usages of a Java symbol referenced from a model expression, so
 * that Find Usages / "Go to Declaration or Usages" (Ctrl/Cmd-B) on a delegate class, bean, or
 * `${bean.method()}` method shows where the model uses it (instead of "no usages").
 *
 * For a **bot** class (a `BotService` implementor) it additionally reports the `.action` models that
 * invoke it — matched by the bot's `getKey()` against each action's `botKey` (a JSON field the
 * expression-based scan below does not see).
 *
 * For a **Spring REST handler** method (`@GetMapping`/`@PostMapping`/…) it reports the model HTTP
 * service tasks whose `requestUrl` resolves to the endpoint — matched by [EndpointModelScan].
 */
class FlowableModelUsageSearcher : CustomUsageSearcher() {

    override fun processElementUsages(element: PsiElement, processor: Processor<in Usage>, options: FindUsagesOptions) {
        ReadAction.runBlocking<RuntimeException> {
            val project = element.project
            if (project.isDisposed) return@runBlocking

            val botKey = (element as? PsiClass)?.let { BotPsi.botKeyOf(it) }
            val endpoint = (element as? PsiMethod)?.let { EndpointPsi.endpointOf(it) }
            val names = ModelReferenceScan.namesOf(element)
            if (botKey == null && endpoint == null && names.isEmpty()) return@runBlocking

            val index = project.service<FlowableModelIndexService>().index()
            val psiManager = PsiManager.getInstance(project)

            // Bot class → the .action models that invoke it (matched by botKey).
            if (botKey != null) {
                for (entry in index.actionsUsingBot(botKey)) {
                    val psiFile = psiManager.findFile(entry.file) ?: continue
                    val text = runCatching { String(entry.file.contentsToByteArray(), Charsets.UTF_8) }.getOrNull()
                    val at = text?.let { botKeyOffset(it, botKey) } ?: -1
                    val usage = if (at >= 0) UsageInfo(psiFile, at, at + botKey.length, false) else UsageInfo(psiFile)
                    processor.process(UsageInfo2UsageAdapter(usage))
                }
            }

            // Spring REST handler → the model HTTP tasks whose requestUrl resolves to its endpoint.
            if (endpoint != null && EndpointModelScan.anyModelCalls(index, endpoint.path)) {
                ModelReferenceScan.forEachModelText(project) { vf, text ->
                    val ranges = EndpointModelScan.usageRanges(text, endpoint.path)
                    if (ranges.isNotEmpty()) {
                        psiManager.findFile(vf)?.let { psiFile ->
                            for (r in ranges) {
                                processor.process(UsageInfo2UsageAdapter(UsageInfo(psiFile, r.first, r.last + 1, false)))
                            }
                        }
                    }
                }
            }

            // Java symbol → the model expressions that reference it by name.
            if (names.isEmpty()) return@runBlocking
            if (names.none { it in index.referencedIdentifiers || it in index.referencedClassFqns }) return@runBlocking

            fun reportUsages(vf: VirtualFile, text: String) {
                if (names.none { text.contains(it) }) return
                val ranges = ModelUsageLocator.findUsages(text, names)
                if (ranges.isEmpty()) return
                val psiFile = psiManager.findFile(vf) ?: return
                for (r in ranges) {
                    processor.process(UsageInfo2UsageAdapter(UsageInfo(psiFile, r.first, r.last + 1, false)))
                }
            }

            ModelReferenceScan.forEachModelText(project, ::reportUsages)
        }
    }

    /** Offset of the bot key value inside an action's `"botKey": "<key>"` field, or -1 if not found. */
    private fun botKeyOffset(text: String, botKey: String): Int {
        val label = text.indexOf("\"botKey\"")
        if (label < 0) return -1
        val valueStart = text.indexOf("\"$botKey\"", label)
        return if (valueStart >= 0) valueStart + 1 else -1
    }
}
