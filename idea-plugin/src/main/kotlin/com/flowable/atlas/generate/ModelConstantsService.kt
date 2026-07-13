package com.flowable.atlas.generate

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.settings.FlowableAtlasProjectSettings
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

    /** User-initiated: generate/overwrite the class at [fqcn] under [sourceRoot], remember it, return the file. */
    fun generateAndWrite(fqcn: String, sourceRoot: VirtualFile): VirtualFile? {
        val source = ProgressManager.getInstance().runProcessWithProgressSynchronously<String, RuntimeException>(
            { buildSource(fqcn) },
            "Generating Flowable Model Constants",
            true,
            project,
        )
        val file = writeFile(fqcn, sourceRoot, source)
        val state = ModelConstantsSettings.getInstance(project).state
        state.fqcn = fqcn
        state.sourceRootUrl = sourceRoot.url
        state.autoRefresh = true
        return file
    }

    /**
     * Auto-refresh: if a constants class was generated and still exists, regenerate it in place.
     * Safe to call from a background thread; the actual write is scheduled on the EDT. Does nothing
     * if auto-refresh is off, no target is configured, or the file was deleted (opt-out).
     */
    fun refresh() {
        val state = ModelConstantsSettings.getInstance(project).state
        if (!state.autoRefresh || state.fqcn.isBlank() || state.sourceRootUrl.isBlank()) return
        val root = VirtualFileManager.getInstance().findFileByUrl(state.sourceRootUrl) ?: return
        val existing = resolveTargetFile(state.fqcn, root) ?: return
        val fqcn = state.fqcn

        val source = buildSource(fqcn)
        val current = ReadAction.compute<String?, RuntimeException> {
            if (existing.isValid) VfsUtilCore.loadText(existing) else null
        }
        if (current == source) return

        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed && existing.isValid) writeFile(fqcn, root, source)
        }
    }

    private fun buildSource(fqcn: String): String {
        val models = ReadAction.compute<List<ModelInfo>, RuntimeException> {
            project.service<FlowableModelIndexService>().index().allDistinct()
                .map { ModelInfo(it.type, it.key, it.name) }
        }
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
