package com.flowable.atlas.usage

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.parsing.ModelUsageLocator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.find.findUsages.CustomUsageSearcher
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.util.Processor

/**
 * Reports Flowable model files as usages of a Java symbol referenced from a model expression, so
 * that Find Usages / "Go to Declaration or Usages" (Ctrl/Cmd-B) on a delegate class, bean, or
 * `${bean.method()}` method shows where the model uses it (instead of "no usages").
 */
class FlowableModelUsageSearcher : CustomUsageSearcher() {

    override fun processElementUsages(element: PsiElement, processor: Processor<in Usage>, options: FindUsagesOptions) {
        ReadAction.runBlocking<RuntimeException> {
            val project = element.project
            if (project.isDisposed) return@runBlocking
            val names = ModelReferenceScan.namesOf(element)
            if (names.isEmpty()) return@runBlocking

            val index = project.service<FlowableModelIndexService>().index()
            if (names.none { it in index.referencedIdentifiers || it in index.referencedClassFqns }) return@runBlocking

            val psiManager = PsiManager.getInstance(project)

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
}
