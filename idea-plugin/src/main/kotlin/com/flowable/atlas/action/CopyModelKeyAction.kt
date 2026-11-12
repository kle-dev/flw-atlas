package com.flowable.atlas.action

import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.completion.SiteMatching
import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.navigation.ModelFileKeySites
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil
import java.awt.datatransfer.StringSelection

/**
 * *Copy Model Key* — the key under the caret onto the clipboard, and nothing else: not the quotes, not
 * the constant's name, not the attribute. In Java a literal or a constant at a Flowable API site; in a
 * model file a cross-reference or the file's own key. The explorer page has had a copy button on every
 * key since the JCEF bridge; the IDE side, where a key is pasted into a test, a log query or a chat,
 * had none. Also the *Recent Models* list's context menu, through [copy].
 */
class CopyModelKeyAction : AnAction(), DumbAware {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = keyAt(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val key = keyAt(e) ?: return
        copy(e.project, key, e.getData(CommonDataKeys.EDITOR))
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun keyAt(e: AnActionEvent): String? {
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return null
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        return keyAt(file, editor.caretModel.offset)
    }

    companion object {
        /** The model key at [offset] of [file], or null when the caret is not on one. */
        fun keyAt(file: PsiFile, offset: Int): String? {
            val leaf = file.findElementAt(offset) ?: return null
            if (ModelFiles.typeOf(file.viewProvider.virtualFile) != null) return ModelFileKeySites.at(leaf)?.key
            return javaKeyAt(leaf)
        }

        private fun javaKeyAt(leaf: PsiElement): String? {
            PsiTreeUtil.getParentOfType(leaf, PsiLiteralExpression::class.java, false)?.let { literal ->
                val value = literal.value as? String ?: return null
                return if (SiteMatching.keySiteForLiteral(literal) != null) value else null
            }
            var ref = PsiTreeUtil.getParentOfType(leaf, PsiReferenceExpression::class.java, false) ?: return null
            while (ref.parent is PsiReferenceExpression) ref = ref.parent as PsiReferenceExpression
            if (ref.parent !is PsiExpressionList) return null
            return SiteMatching.keySiteForArgument(ref)?.second
        }

        /** Puts [key] on the clipboard and says so — in the editor as a hint, else in the status bar. */
        fun copy(project: Project?, key: String, editor: Editor?) {
            CopyPasteManager.getInstance().setContents(StringSelection(key))
            val note = message("copyKey.copied", key)
            if (editor != null) HintManager.getInstance().showInformationHint(editor, note)
            else project?.let { WindowManager.getInstance().getStatusBar(it)?.info = note }
        }
    }
}
