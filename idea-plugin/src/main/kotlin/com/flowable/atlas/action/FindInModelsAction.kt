package com.flowable.atlas.action

import com.flowable.atlas.FlowableAtlasBundle.message
import com.flowable.atlas.usage.ModelSearchUsages
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages

/**
 * *Find in Models…* — every occurrence of a string in every Flowable model, as a Find-tool-window list
 * that stays open.
 *
 * The sibling of *Search Models…*, and the difference is the whole point of it: the search popup is for
 * getting to **one** place quickly and closes when you do; this is for working through **all** of them.
 * Both read the same index and the same model text, archive entries included, so a hit one finds the
 * other finds too. The popup also hands over directly — ⇧⏎ there opens the same list.
 *
 * Prefilled from the editor: the selection, else the model key under the caret (the same one
 * [CopyModelKeyAction] copies), so searching for the key you are looking at costs no typing.
 */
class FindInModelsAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val pattern = Messages.showInputDialog(
            project,
            message("findInModels.prompt"),
            message("findInModels.title"),
            AllIcons.Actions.Find,
            preset(e),
            LongEnough,
        )?.trim() ?: return
        if (pattern.length < ModelSearchUsages.MIN_PATTERN_LENGTH) return
        ModelSearchUsages.show(project, pattern)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    /** EDT: the dialog is modal and the preset reads PSI, which the EDT may do without a read action. */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    private fun preset(e: AnActionEvent): String {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return ""
        editor.selectionModel.selectedText?.trim()?.takeIf { it.isNotEmpty() && !it.contains('\n') }?.let { return it }
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return ""
        return CopyModelKeyAction.keyAt(file, editor.caretModel.offset).orEmpty()
    }

    /** One character matches most of every model's text — the dialog says so rather than searching. */
    private object LongEnough : InputValidator {
        override fun checkInput(inputString: String?): Boolean =
            (inputString?.trim()?.length ?: 0) >= ModelSearchUsages.MIN_PATTERN_LENGTH

        override fun canClose(inputString: String?): Boolean = checkInput(inputString)
    }
}
