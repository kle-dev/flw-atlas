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

    /** What one Find Usages run needs from the PSI element — read once, under a read action. */
    private data class Subject(
        val botKey: String?,
        val endpoints: List<EndpointPsi.Endpoint>,
        val names: Set<String>,
    )

    override fun processElementUsages(element: PsiElement, processor: Processor<in Usage>, options: FindUsagesOptions) {
        val project = element.project
        if (project.isDisposed) return

        // Phase 1 — PSI only, under a short read action.
        val subject = ReadAction.computeBlocking<Subject, RuntimeException> {
            Subject(
                botKey = (element as? PsiClass)?.let { BotPsi.botKeyOf(it) },
                endpoints = (element as? PsiMethod)?.let { EndpointPsi.endpointsOf(it) }.orEmpty(),
                names = ModelReferenceScan.namesOf(element),
            )
        }
        val (botKey, endpoints, names) = subject
        if (botKey == null && endpoints.isEmpty() && names.isEmpty()) return

        // Phase 2 — the index, deliberately OUTSIDE any read action. On a cold cache this is a full
        // model scan, and holding the read lock across it makes every write action (typing, a VFS
        // refresh) queue behind Find Usages. FlowableModelIndexService already splits itself into a
        // short read action for collecting the files and a lock-free parse; that split only buys
        // anything if the caller does not wrap the whole thing in a read action again.
        val index = project.service<FlowableModelIndexService>().index()

        // Phase 3 — reporting, back under a read action (PsiManager / PsiFile / UsageInfo).
        ReadAction.runBlocking<RuntimeException> {
            if (project.isDisposed) return@runBlocking
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
            val calledEndpoints = endpoints.filter { EndpointModelScan.anyModelCalls(index, it) }
            if (calledEndpoints.isNotEmpty()) {
                ModelReferenceScan.forEachModelText(project) { vf, text ->
                    val ranges = EndpointModelScan.usageRanges(text, calledEndpoints)
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
