package com.flowable.atlas.expr.intention

import com.flowable.atlas.expr.lang.FlowableExprFile
import com.flowable.atlas.expr.lang.dialectOf
import com.flowable.atlas.expr.toolwindow.FlowableExpressionPanel
import com.flowable.atlas.index.FlowableModelIndexService
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Alt+Enter on an injected `${…}` / `{{…}}` fragment → open it in the Expression Playground,
 * pre-filled with the fragment text, the right dialect, and — when the host file is an indexed
 * model — the model scope. Only offered on injected fragments (in the playground's own field it
 * would be a no-op).
 */
class OpenInExpressionPlaygroundIntention : IntentionAction, DumbAware {

    override fun getText(): String = "Open in Expression Playground"
    override fun getFamilyName(): String = "Flowable expression"
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        file is FlowableExprFile && InjectedLanguageManager.getInstance(project).isInjectedFragment(file)

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (file !is FlowableExprFile) return
        val dialect = dialectOf(file.language) ?: return
        FlowableExpressionPanel.open(project, file.text, dialect, scopeKeyOf(project, file))
    }

    /** The enclosing model's key — cached index only, never a blocking scan (we're on the EDT). */
    private fun scopeKeyOf(project: Project, file: PsiFile): String? {
        val host = InjectedLanguageManager.getInstance(project).getInjectionHost(file) ?: return null
        val vFile = host.containingFile?.virtualFile ?: return null
        return project.service<FlowableModelIndexService>().cachedOrNull()
            ?.allDistinct()?.firstOrNull { it.file == vFile }?.key
    }

    // Opens a tool window, mutates no file — the default preview would render an empty diff.
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.Html("Opens this expression in the Flowable Expressions playground, scoped to the enclosing model.")
}
