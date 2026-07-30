package com.flowable.atlas.generate.dto

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.intention.DataObjectBeanGenerator
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.parsing.DataField
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.flowable.atlas.settings.GenerationConfigurable
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager

/**
 * Backs the "Generate → Data-Object DTOs" dialog ([GenerateDataObjectDtoDialog]), split like its
 * Liquibase sibling ([com.flowable.atlas.generate.liquibase.LiquibaseScaffoldService]):
 *
 *  - **plan** ([computePlans]) — a read-only pass over the model index resolving every data object's
 *    fields, owning app(s) and default class name. No file is touched; the dialog renders it as a
 *    preview. Must run under a read action (the caller wraps).
 *  - **write** ([writeResolved]) — the user's confirmed selection in a single write command: one
 *    `*.java` per DTO under the chosen source root, packages created as needed, each reformatted with
 *    the project's code style.
 *  - **report** ([reportGenerated]) — opens the first file and raises the summary notification.
 *
 * The generated source itself comes from [DataObjectBeanGenerator] — the very generator the Alt-Enter
 * intention uses — so a DTO written in bulk and one written from the editor can never drift apart.
 * Nothing outside the chosen source root is ever created or modified.
 */
@Service(Service.Level.PROJECT)
class DataObjectDtoService(private val project: Project) {

    // ---- plan model ---------------------------------------------------------------------------

    /**
     * One candidate DTO for the preview: the data object's [key] and [modelName], the [apps] that own
     * it, the [defaultClassName] the editable column starts from, and the [fields] the class is
     * generated from. A data object with no fields stays visible (so its absence is explained) but
     * cannot be generated — see [generatable].
     */
    data class DtoPlanItem(
        val key: String,
        val modelName: String,
        val apps: List<AppRef>,
        val defaultClassName: String,
        val fields: List<DataField>,
    ) {
        /** Nothing to map → no class worth writing; the dialog shows the row disabled. */
        val generatable: Boolean get() = fields.isNotEmpty()

        val appLabel: String get() = apps.joinToString(", ") { it.label }.ifEmpty { "—" }

        /** The app whose sub-package this DTO lands in when no single app is being generated. */
        val primaryApp: AppRef? get() = apps.firstOrNull()
    }

    /** The full preview: every data object plus the distinct apps, so the dialog's combo is instant. */
    data class Plans(val items: List<DtoPlanItem>, val apps: List<AppRef>) {

        fun itemsOfApp(appKey: String): List<DtoPlanItem> = items.filter { item -> item.apps.any { it.key == appKey } }
    }

    /** A confirmed write: the source-root-relative [path] and the Java [source] to store. */
    data class DtoWrite(val path: String, val source: String)

    // ---- entry point --------------------------------------------------------------------------

    /**
     * Compute the preview off the EDT and open [GenerateDataObjectDtoDialog] with [source] preselected.
     * When no data object is indexed at all, a notification is shown instead of an empty dialog. Both
     * "Generate → Data-Object DTOs" menu actions funnel through here.
     */
    fun openDialog(source: DtoSource) {
        object : Task.Backgroundable(project, "Preparing data-object DTO preview", true) {
            override fun run(indicator: ProgressIndicator) {
                val plans = try {
                    // Build the index (when cold) *before* taking the read action: the scan manages its
                    // own short read actions on purpose, and must not run with the lock already held.
                    project.service<FlowableModelIndexService>().index()
                    ReadAction.computeBlocking<Plans, RuntimeException> { computePlans() }
                } catch (pce: ProcessCanceledException) {
                    throw pce
                } catch (t: Throwable) {
                    thisLogger().warn("Data-object DTO preview failed", t)
                    return notifyLater("DTO preview failed", t.message ?: t.javaClass.simpleName, NotificationType.ERROR)
                }
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    if (plans.items.isEmpty()) {
                        notify(
                            "Nothing to generate",
                            "No data objects are indexed in this project.",
                            NotificationType.INFORMATION,
                        )
                    } else {
                        GenerateDataObjectDtoDialog(project, plans, source).show()
                    }
                }
            }
        }.queue()
    }

    // ---- plan ---------------------------------------------------------------------------------

    /**
     * Resolve every indexed data object into a [Plans] preview: its fields, its owning app(s) and the
     * class name to propose. Read access required — call inside a read action.
     */
    fun computePlans(): Plans {
        val index = project.service<FlowableModelIndexService>()
        val suffix = FlowableAtlasProjectSettings.getInstance(project).dtoClassSuffix
        val entries = index.keysOfType(ModelType.DATA_OBJECT).distinctBy { it.key }.sortedBy { it.key }
        val byApp = DataObjectApps.group(index.keysOfType(ModelType.APP).distinctBy { it.key }, entries)

        val items = entries.map { entry ->
            val fields = index.dataObjectInfoOf(entry.key)?.fieldMappings.orEmpty()
            DtoPlanItem(
                key = entry.key,
                modelName = entry.name,
                apps = byApp[entry.key].orEmpty(),
                defaultClassName = DataObjectDtoPlanner.defaultClassName(entry.name, entry.key, suffix),
                fields = fields,
            )
        }
        // Real apps first, guessed archive groupings last — the combo reads top-down.
        val apps = items.flatMap { it.apps }.distinctBy { it.key }.sortedWith(compareBy({ it.synthetic }, { it.key }))
        return Plans(items, apps)
    }

    // ---- write --------------------------------------------------------------------------------

    /**
     * Write each of [writes] to `<sourceRoot>/<path>`, creating package directories as needed. Files
     * that already exist are overwritten unless [skipExisting]. One write command; each written file is
     * reformatted with the project's code style. Returns the files actually written.
     */
    fun writeResolved(sourceRoot: VirtualFile, writes: List<DtoWrite>, skipExisting: Boolean): List<VirtualFile> {
        val written = ArrayList<VirtualFile>()
        WriteCommandAction.runWriteCommandAction(project, "Generate Flowable Data-Object DTOs", null, {
            for (w in writes) {
                val dirPath = w.path.substringBeforeLast('/', "")
                val fileName = w.path.substringAfterLast('/')
                val dir = if (dirPath.isEmpty()) sourceRoot else VfsUtil.createDirectoryIfMissing(sourceRoot, dirPath)
                val existing = dir.findChild(fileName)
                if (existing != null && skipExisting) continue
                val file = existing ?: dir.createChildData(this, fileName)
                VfsUtil.saveText(file, w.source)
                written.add(file)
            }
            reformat(written)
        })
        return written
    }

    /** Apply the project's code style to the generated files; a failure must never lose the file. */
    private fun reformat(files: List<VirtualFile>) {
        val psiManager = PsiManager.getInstance(project)
        val documentManager = PsiDocumentManager.getInstance(project)
        val codeStyle = CodeStyleManager.getInstance(project)
        for (file in files) {
            runCatching {
                val psi = psiManager.findFile(file) ?: return@runCatching
                documentManager.getDocument(psi)?.let { documentManager.commitDocument(it) }
                codeStyle.reformat(psi)
            }.onFailure { thisLogger().debug("could not reformat ${file.path}", it) }
        }
    }

    // ---- report -------------------------------------------------------------------------------

    /** Open the first written file and raise the summary notification (with a settings deep-link). */
    fun reportGenerated(written: List<VirtualFile>, skipped: Int, target: String) {
        if (project.isDisposed) return
        written.firstOrNull()?.let { FileEditorManager.getInstance(project).openFile(it, true) }
        val skippedNote = if (skipped > 0) " $skipped file(s) already existed and were kept." else ""
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                "Generated ${written.size} data-object DTO(s)",
                "Written under $target.$skippedNote Each class maps its data object's fields and carries " +
                    "a fromContainer(…) mapper and a fluent builder.",
                NotificationType.INFORMATION,
            )
        notification.addAction(
            NotificationAction.createSimple("Open Generation settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, GenerationConfigurable::class.java)
            },
        )
        notification.notify(project)
    }

    private fun notify(title: String, message: String, type: NotificationType) {
        if (project.isDisposed) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, message, type)
            .notify(project)
    }

    private fun notifyLater(title: String, message: String, type: NotificationType) =
        ApplicationManager.getApplication().invokeLater { notify(title, message, type) }

    companion object {
        private const val GROUP_ID = "Flowable Atlas"

        fun getInstance(project: Project): DataObjectDtoService = project.service()
    }
}
