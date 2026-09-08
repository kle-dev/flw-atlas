package com.flowable.atlas.expr.intention

import com.flowable.atlas.expr.lang.FlowableExprFile
import com.flowable.atlas.expr.lang.dialectOf
import com.flowable.atlas.expr.toolwindow.FlowableExpressionPanel
import com.flowable.atlas.expr.inspect.InspectClient
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.index.ModelEntry
import com.flowable.atlas.model.ModelType
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
    override fun getFamilyName(): String = "Open in Expression Playground"
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        file is FlowableExprFile && InjectedLanguageManager.getInstance(project).isInjectedFragment(file)

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (file !is FlowableExprFile) return
        val dialect = dialectOf(file.language) ?: return
        val host = hostModel(project, file)
        FlowableExpressionPanel.open(
            project,
            FlowableExpressionPanel.OpenRequest(file.text, dialect, host?.key, host?.type?.let(::scopeTypeOf)),
        )
    }

    /** The enclosing model — cached index only, never a blocking scan (we're on the EDT). */
    private fun hostModel(project: Project, file: PsiFile): ModelEntry? {
        val host = InjectedLanguageManager.getInstance(project).getInjectionHost(file) ?: return null
        val vFile = host.containingFile?.virtualFile ?: return null
        return project.service<FlowableModelIndexService>().cachedOrNull()
            ?.allDistinct()?.firstOrNull { it.file == vFile }
    }

    /** A BPMN model evaluates against a process instance, a CMMN one against a case — preset, not guessed later. */
    private fun scopeTypeOf(type: ModelType): InspectClient.ScopeType? = when (type) {
        ModelType.PROCESS -> InspectClient.ScopeType.BPMN
        ModelType.CASE -> InspectClient.ScopeType.CMMN
        else -> null
    }

    // Opens a tool window, mutates no file — the default preview would render an empty diff.
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.Html("Opens this expression in the Flowable Expressions playground, scoped to the enclosing model. The playground's previous expression for this dialect is replaced (Ctrl+Z there brings it back).")
}
