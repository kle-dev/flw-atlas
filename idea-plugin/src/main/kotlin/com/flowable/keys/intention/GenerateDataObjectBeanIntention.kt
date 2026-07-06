package com.flowable.keys.intention

import com.flowable.keys.completion.SiteMatching
import com.flowable.keys.index.FlowableModelIndexService
import com.flowable.keys.model.ModelType
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * Alt-Enter on a data-object `definitionKey("…")` literal → generates a typed Java bean from the
 * data object's `fieldMappings`, so query results can be mapped onto a POJO instead of the generic
 * `DataObjectInstanceVariableContainer`. The bean is created next to the current file.
 */
class GenerateDataObjectBeanIntention : PsiElementBaseIntentionAction() {

    override fun getText(): String = "Generate Java bean for this Flowable data object"

    override fun getFamilyName(): String = "Flowable Keys"

    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val key = dataObjectKeyAt(element) ?: return false
        val info = project.service<FlowableModelIndexService>().dataObjectInfoOf(key) ?: return false
        return info.fieldMappings.isNotEmpty()
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val key = dataObjectKeyAt(element) ?: return
        val service = project.service<FlowableModelIndexService>()
        val info = service.dataObjectInfoOf(key) ?: return
        if (info.fieldMappings.isEmpty()) return

        val javaFile = element.containingFile as? PsiJavaFile ?: return
        val dir = javaFile.containingDirectory ?: return
        val pkg = javaFile.packageName.takeIf { it.isNotEmpty() }
        val entryName = service.find(key).firstOrNull { it.type == ModelType.DATA_OBJECT }?.name
        val defaultName = DataObjectBeanGenerator.classNameFor(entryName, key)

        // Let the user name the class (default = derived from the model name / key).
        val className = Messages.showInputDialog(
            project,
            "Class name for the bean mapping data object '$key':",
            "Generate Flowable Data-Object Bean",
            Messages.getQuestionIcon(),
            defaultName,
            object : InputValidator {
                override fun checkInput(inputString: String?): Boolean = isValidClassName(inputString)
                override fun canClose(inputString: String?): Boolean = isValidClassName(inputString)
            },
        )?.trim()?.takeIf { it.isNotBlank() } ?: return

        dir.findFile("$className.java")?.let { existing ->
            existing.virtualFile?.let { FileEditorManager.getInstance(project).openFile(it, true) }
            return
        }

        val source = DataObjectBeanGenerator.generate(pkg, className, key, info.fieldMappings)
        WriteCommandAction.runWriteCommandAction(project) {
            val psi = PsiFileFactory.getInstance(project).createFileFromText("$className.java", JavaFileType.INSTANCE, source)
            val added = dir.add(psi) as? PsiFile
            added?.let { CodeStyleManager.getInstance(project).reformat(it) }
            added?.virtualFile?.let { FileEditorManager.getInstance(project).openFile(it, true) }
        }
    }

    private fun isValidClassName(name: String?): Boolean {
        val n = name?.trim() ?: return false
        return n.matches(Regex("[A-Za-z_\$][A-Za-z0-9_\$]*"))
    }

    /** The data-object key if [element] sits in a `String` literal at a DATA_OBJECT key call site. */
    private fun dataObjectKeyAt(element: PsiElement): String? {
        val literal = PsiTreeUtil.getParentOfType(element, PsiLiteralExpression::class.java, false) ?: return null
        val site = SiteMatching.keySiteForLiteral(literal) ?: return null
        if (ModelType.DATA_OBJECT !in site.targetTypes) return null
        return literal.value as? String
    }
}
