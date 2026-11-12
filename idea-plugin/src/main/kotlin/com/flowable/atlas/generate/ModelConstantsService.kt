package com.flowable.atlas.generate

import com.flowable.atlas.AtlasNotifications
import com.flowable.atlas.action.GenerateModelConstantsAction
import com.flowable.atlas.index.FlowableIndex
import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.project.AtlasProjectRootService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager

/** Generates and (re)writes the project's Flowable model-constants class. */
@Service(Service.Level.PROJECT)
class ModelConstantsService(private val project: Project) {

    /**
     * User-initiated: generate/overwrite the class at [fqcn] under [sourceRoot], remember it, say what happened.
     * The text is built before the file is touched, so a build that fails or is cancelled leaves no empty
     * `.java` behind.
     */
    fun generateAndWrite(fqcn: String, sourceRoot: VirtualFile): Result {
        // index(), not cachedOrRequest(): this is the user asking, behind a modal progress that exists so it
        // can wait. A cold index — the scan after opening the project, after any model save — used to come
        // back null here and end in a NullPointerException balloon. The task's thread holds no read lock,
        // so index() joins the background build (or starts one, retrying a failed one) and honours Cancel;
        // a build that fails reaches the action's error balloon with its own message.
        val source: String = ProgressManager.getInstance().runProcessWithProgressSynchronously<String, RuntimeException>(
            { buildSource(fqcn, project.service<FlowableModelIndexService>().index()) },
            "Generating Flowable Model Constants",
            true,
            project,
        )
        val file = writeFile(fqcn, sourceRoot, source) ?: return Result.NotWritten
        val state = ModelConstantsSettings.getInstance(project).state
        state.fqcn = fqcn
        state.sourceRootUrl = sourceRoot.url
        // Not `autoRefresh = true`: that is the settings page's checkbox, and generating by hand is exactly
        // what someone who switched it off does — it used to be ticked again behind their back.
        state.subProject = AtlasProjectRootService.getInstance(project).activeSubProject()
        return Result.Written(file)
    }

    /** What [generateAndWrite] did. */
    sealed interface Result {
        data class Written(val file: VirtualFile) : Result
        data object NotWritten : Result
    }

    /**
     * Auto-refresh: if a constants class was generated and still exists, regenerate it in place.
     * Safe to call from a background thread; the actual write is scheduled on the EDT. Does nothing
     * if auto-refresh is off, no target is configured, the file was deleted (opt-out), or another
     * Flowable project than the one it was generated from is active.
     */
    fun refresh() {
        val state = ModelConstantsSettings.getInstance(project).state
        if (!state.autoRefresh || state.fqcn.isBlank() || state.sourceRootUrl.isBlank()) return
        // The index and the naming follow the project picked in the Hub; the file does not. Rewriting it on
        // a switch put the other app's keys into a class this app's code compiles against. The stored key
        // is compared as it is, not resolved: a key that no longer resolves would widen to the whole
        // repository, which is not what the class was generated from either. Switching back brings the
        // next index build round, and the class is synced again.
        if (state.subProject != AtlasProjectRootService.getInstance(project).activeSubProject()) return
        val root = VirtualFileManager.getInstance().findFileByUrl(state.sourceRootUrl) ?: return
        val existing = resolveTargetFile(state.fqcn, root) ?: return
        val fqcn = state.fqcn

        // A cold index is asked for, not built here under the read lock; the rebuild it triggers ends in
        // another modelIndexUpdated, which brings the refresher (ModelConstantsAutoRefresher) round again.
        val index = project.service<FlowableModelIndexService>().cachedOrRequest() ?: return
        val source = buildSource(fqcn, index)
        val current = ReadAction.computeBlocking<String?, RuntimeException> {
            if (existing.isValid) VfsUtilCore.loadText(existing) else null
        }
        if (current == source) return

        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed && existing.isValid) writeFile(fqcn, root, source)
        }
    }

    /**
     * The configured class name changed. The file generated under [oldFqcn] stays where it is and is no
     * longer kept in sync — said in a balloon, with the way to get the new class, instead of going quiet.
     */
    fun noteRenamed(oldFqcn: String) {
        val state = ModelConstantsSettings.getInstance(project).state
        val root = VirtualFileManager.getInstance().findFileByUrl(state.sourceRootUrl) ?: return
        val old = resolveTargetFile(oldFqcn, root) ?: return
        AtlasNotifications.group()
            .createNotification(
                "Model constants class renamed",
                "${old.name} was generated as $oldFqcn and stays where it is, no longer kept in sync. " +
                    "Generate Model Constants creates ${state.fqcn.ifBlank { "the default class" }}.",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimple("Generate now") { GenerateModelConstantsAction.generate(project) })
            .notify(project)
    }

    private fun buildSource(fqcn: String, index: FlowableIndex): String {
        val models = index.allDistinct().map { ModelInfo(it.type, it.key, it.name) }
        val settings = FlowableAtlasProjectSettings.getInstance(project)
        return ModelConstantsGenerator.generate(models, fqcn, settings.constantNaming, settings.constantFormat)
    }

    private fun resolveTargetFile(fqcn: String, root: VirtualFile): VirtualFile? {
        val pkgPath = fqcn.substringBeforeLast('.', "").replace('.', '/')
        val rel = (if (pkgPath.isEmpty()) "" else "$pkgPath/") + fqcn.substringAfterLast('.') + ".java"
        return root.findFileByRelativePath(rel)
    }

    private fun writeFile(fqcn: String, root: VirtualFile, source: String): VirtualFile? {
        var result: VirtualFile? = null
        WriteCommandAction.runWriteCommandAction(project, "Generate Flowable Model Constants", null, {
            val pkgPath = fqcn.substringBeforeLast('.', "").replace('.', '/')
            val dir = if (pkgPath.isEmpty()) root else VfsUtil.createDirectoryIfMissing(root, pkgPath)
            val name = fqcn.substringAfterLast('.') + ".java"
            val file = dir.findChild(name) ?: dir.createChildData(this, name)
            VfsUtil.saveText(file, source)
            result = file
        })
        return result
    }

    companion object {
        fun getInstance(project: Project): ModelConstantsService = project.service()
    }
}
