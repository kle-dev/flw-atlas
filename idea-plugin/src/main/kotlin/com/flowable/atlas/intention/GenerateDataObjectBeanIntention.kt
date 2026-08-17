package com.flowable.atlas.intention

import com.flowable.atlas.generate.dto.DataObjectDtoPlanner
import com.flowable.atlas.generate.dto.DtoClassNamePattern
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.parsing.DataObjectInfo
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.codeStyle.CodeStyleManager

/**
 * Alt-Enter on a Flowable data-object key → generates a typed Java DTO from the data object's
 * `fieldMappings`, so query results can be mapped onto a POJO instead of the generic
 * `DataObjectInstanceVariableContainer`. The class is created next to the current file.
 *
 * "A data-object key" is whatever [DataObjectKeyAtCaret] recognizes: the argument of a catalogued
 * data-object API call — inline literal **or** constant reference, so the plugin's own generated model
 * constants work — and, beyond call sites, any String literal/constant whose value is an indexed
 * data-object key. Availability is a pure index lookup; reading the model's fields happens under a
 * progress in [invoke], never during the highlighting pass.
 *
 * The bulk equivalent is Tools → Flowable Atlas → Generate → Data-Object DTOs; both emit the same
 * source via [DataObjectBeanGenerator] and share the class-name rules in [DataObjectDtoPlanner].
 */
class GenerateDataObjectBeanIntention : PsiElementBaseIntentionAction() {

    override fun getText(): String = "Generate Java DTO for this Flowable data object"

    override fun getFamilyName(): String = "Flowable Atlas"

    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean =
        DataObjectKeyAtCaret.resolve(project, element) != null

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val key = DataObjectKeyAtCaret.resolve(project, element) ?: return
        val javaFile = element.containingFile as? PsiJavaFile ?: return
        val dir = javaFile.containingDirectory ?: return
        val pkg = javaFile.packageName.takeIf { it.isNotEmpty() }

        // Reading the model (and building the index on a cold start) can take a moment — do it under a
        // cancellable progress rather than freezing the editor, and say so when nothing comes back.
        val resolved = ProgressManager.getInstance().runProcessWithProgressSynchronously<Resolved?, RuntimeException>(
            { resolve(project, key) },
            "Resolving Flowable Data Object",
            true,
            project,
        )
        if (resolved == null || resolved.info.fieldMappings.isEmpty()) {
            notify(
                project,
                "No DTO generated for '$key'",
                if (resolved == null) "No data-object model with this key is indexed in the project."
                else "The data object declares no field mappings, so there is nothing to map onto a class.",
            )
            return
        }

        // The same class-name pattern the bulk dialog renders, so both propose the same name for the
        // same data object. {app} stays empty here: an editor caret knows the key, not an owning app.
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        val defaultName = DtoClassNamePattern.className(
            settings.dtoClassNamePattern,
            DtoClassNamePattern.deriveTokens(key, resolved.modelName, appKey = null, suffix = settings.dtoClassSuffix),
            settings.dtoRenameFind,
            settings.dtoRenameReplace,
        )

        // Let the user name the class (default = derived from the model name / key).
        val className = Messages.showInputDialog(
            project,
            "Class name for the DTO mapping data object '$key':",
            "Generate Flowable Data-Object DTO",
            Messages.getQuestionIcon(),
            defaultName,
            object : InputValidator {
                override fun checkInput(inputString: String?): Boolean = isValidClassName(inputString)
                override fun canClose(inputString: String?): Boolean = isValidClassName(inputString)
            },
        )?.trim()?.takeIf { it.isNotBlank() } ?: return

        val source = DataObjectBeanGenerator.generate(pkg, className, key, resolved.info.fieldMappings)

        val existing = dir.findFile("$className.java")
        if (existing != null) {
            overwrite(project, existing, className, source)
            return
        }

        WriteCommandAction.runWriteCommandAction(project) {
            val psi = PsiFileFactory.getInstance(project).createFileFromText("$className.java", JavaFileType.INSTANCE, source)
            val added = dir.add(psi) as? PsiFile
            added?.let { CodeStyleManager.getInstance(project).reformat(it) }
            added?.virtualFile?.let { FileEditorManager.getInstance(project).openFile(it, true) }
        }
    }

    /** Ask before replacing an existing class, then regenerate it in place (or just open it). */
    private fun overwrite(project: Project, existing: PsiFile, className: String, source: String) {
        val confirmed = Messages.showYesNoDialog(
            project,
            "Class '$className' already exists. Overwrite it with the regenerated DTO (builder + mapper)?",
            "Generate Flowable Data-Object DTO",
            Messages.getQuestionIcon(),
        )
        if (confirmed != Messages.YES) {
            existing.virtualFile?.let { FileEditorManager.getInstance(project).openFile(it, true) }
            return
        }
        WriteCommandAction.runWriteCommandAction(project) {
            val docManager = PsiDocumentManager.getInstance(project)
            docManager.getDocument(existing)?.let { doc ->
                doc.setText(source)
                docManager.commitDocument(doc)
                CodeStyleManager.getInstance(project).reformat(existing)
            }
            existing.virtualFile?.let { FileEditorManager.getInstance(project).openFile(it, true) }
        }
    }

    /** The model's fields plus its display name; resolved off the EDT (both can build the index). */
    private fun resolve(project: Project, key: String): Resolved? {
        val service = project.service<FlowableModelIndexService>()
        val info = service.dataObjectInfoOf(key) ?: return null
        val name = service.find(key).firstOrNull { it.type == ModelType.DATA_OBJECT }?.name
        return Resolved(info, name)
    }

    private data class Resolved(val info: DataObjectInfo, val modelName: String?)

    private fun notify(project: Project, title: String, message: String) =
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Flowable Atlas")
            .createNotification(title, message, NotificationType.INFORMATION)
            .notify(project)

    private fun isValidClassName(name: String?): Boolean =
        name?.trim()?.let { DataObjectDtoPlanner.isValidClassName(it) } ?: false
}
