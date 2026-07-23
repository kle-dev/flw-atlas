package com.flowable.atlas.generate.liquibase

import com.flowable.atlas.index.FlowableModelIndexService
import com.flowable.atlas.liquibase.LiquibaseChangelog
import com.flowable.atlas.model.ModelPaths
import com.flowable.atlas.model.ModelType
import com.flowable.atlas.parsing.ServiceTable
import com.flowable.atlas.project.AtlasProjectRootService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Writes Liquibase changelogs into developer code for the "Generate → Liquibase" actions. Two entry
 * points ([generateFromDataObject], [generateFromApps]) do their heavy work — zip reads, model
 * lookups, synthesis — off the EDT (a background task + a read action), then apply the file writes in
 * a single write command on the EDT.
 *
 * Every changelog lands in `src/main/resources/liquibase/<key>.changelog.xml` under the active
 * project. The master `flowable-project-db-changelog.xml` (the convention path Flowable auto-runs) is
 * created from a skeleton when absent and otherwise left alone — only the missing `<include>` line is
 * added, idempotently (see [LiquibaseChangelogGenerator.withInclude]).
 */
@Service(Service.Level.PROJECT)
class LiquibaseScaffoldService(private val project: Project) {

    /** A changelog to write: the file base [key] and its [xml] body. */
    data class GeneratedChangelog(val key: String, val xml: String)

    // ---- public entry points ---------------------------------------------------------------

    /** Generate the changelog backing the picked [dataObjectKey] (extract if bundled, else synthesize). */
    fun generateFromDataObject(dataObjectKey: String) {
        object : Task.Backgroundable(project, "Generating Liquibase changelog", true) {
            override fun run(indicator: ProgressIndicator) = doGenerateFromDataObject(dataObjectKey)
        }.queue()
    }

    /** Extract every bundled changelog from every Design-export app zip in the project. */
    fun generateFromApps() {
        object : Task.Backgroundable(project, "Extracting Liquibase changelogs", true) {
            override fun run(indicator: ProgressIndicator) = doGenerateFromApps()
        }.queue()
    }

    private fun doGenerateFromDataObject(dataObjectKey: String) {
        try {
            val base = projectDirFile()
                ?: return notifyLater("Cannot generate changelog", "No project directory on disk.", NotificationType.ERROR)
            val resolved = ReadAction.computeBlocking<Resolved?, RuntimeException> { resolveForDataObject(dataObjectKey, base) }
                ?: return notifyLater("No data object '$dataObjectKey'", "It is not indexed as a data object.", NotificationType.WARNING)
            applyOnEdt(base, listOf(resolved.changelog)) { written ->
                val file = written.firstOrNull()
                file?.let { FileEditorManager.getInstance(project).openFile(it, true) }
                notify(
                    "Generated ${file?.name ?: "${resolved.changelog.key}.changelog.xml"}",
                    "${resolved.origin}. Registered in ${LiquibaseChangelogGenerator.MASTER_CHANGELOG}.",
                    NotificationType.INFORMATION,
                )
            }
        } catch (pce: ProcessCanceledException) {
            throw pce
        } catch (t: Throwable) {
            thisLogger().warn("Generate Liquibase from data object failed", t)
            notifyLater("Generate Liquibase changelog failed", t.message ?: t.javaClass.simpleName, NotificationType.ERROR)
        }
    }

    private fun doGenerateFromApps() {
        try {
            val base = projectDirFile()
                ?: return notifyLater("Cannot extract changelogs", "No project directory on disk.", NotificationType.ERROR)
            val extracted = ReadAction.computeBlocking<List<LiquibaseChangelogExtractor.Extracted>, RuntimeException> {
                archivesUnder(base).flatMap { LiquibaseChangelogExtractor.extract(it) }
            }
            val changelogs = extracted.distinctBy { it.key }.map { GeneratedChangelog(it.key, it.xml) }
            if (changelogs.isEmpty()) {
                return notifyLater("No bundled changelogs found", "No app export in the project bundles a Liquibase changelog.", NotificationType.INFORMATION)
            }
            applyOnEdt(base, changelogs) { written ->
                written.firstOrNull()?.let { FileEditorManager.getInstance(project).openFile(it, true) }
                notify(
                    "Extracted ${written.size} Liquibase changelog(s)",
                    "Written under src/main/resources/liquibase and registered in ${LiquibaseChangelogGenerator.MASTER_CHANGELOG}.",
                    NotificationType.INFORMATION,
                )
            }
        } catch (pce: ProcessCanceledException) {
            throw pce
        } catch (t: Throwable) {
            thisLogger().warn("Generate Liquibase from apps failed", t)
            notifyLater("Extract Liquibase changelogs failed", t.message ?: t.javaClass.simpleName, NotificationType.ERROR)
        }
    }

    // ---- write pipeline (testable) ----------------------------------------------------------

    /**
     * Write each of [changelogs] to `src/main/resources/liquibase/<key>.changelog.xml` under
     * [projectDir], create the master skeleton when absent and register each file via an idempotent
     * `<include>`. One write command; returns the per-changelog files written (master excluded).
     */
    fun writeChangelogs(projectDir: VirtualFile, changelogs: List<GeneratedChangelog>): List<VirtualFile> {
        val written = ArrayList<VirtualFile>()
        WriteCommandAction.runWriteCommandAction(project, "Generate Liquibase Changelog", null, {
            val dir = VfsUtil.createDirectoryIfMissing(projectDir, LIQUIBASE_DIR)
            val master = dir.findChild(LiquibaseChangelogGenerator.MASTER_CHANGELOG)
                ?: dir.createChildData(this, LiquibaseChangelogGenerator.MASTER_CHANGELOG)
                    .also { VfsUtil.saveText(it, LiquibaseChangelogGenerator.masterSkeleton()) }

            var masterText = VfsUtilCore.loadText(master)
            for (cl in changelogs) {
                val fileName = fileNameFor(cl.key)
                val file = dir.findChild(fileName) ?: dir.createChildData(this, fileName)
                VfsUtil.saveText(file, cl.xml)
                written.add(file)
                masterText = LiquibaseChangelogGenerator.withInclude(masterText, fileName)
            }
            if (masterText != VfsUtilCore.loadText(master)) VfsUtil.saveText(master, masterText)
        })
        return written
    }

    // ---- resolution / matching --------------------------------------------------------------

    private data class Resolved(val changelog: GeneratedChangelog, val origin: String)

    /**
     * The changelog for [dataObjectKey]: the bundled one when a matching app-export changelog exists
     * (emitted verbatim), otherwise a synthesized fallback from the data object's fields. Read access
     * required (model files are read directly).
     */
    private fun resolveForDataObject(dataObjectKey: String, base: VirtualFile): Resolved? {
        val index = project.service<FlowableModelIndexService>()
        val info = index.dataObjectInfoOf(dataObjectKey) ?: return null
        val service = info.referencedServiceDefinitionModelKey?.let { index.serviceTableOf(it) }
        val candidates = archivesUnder(base).flatMap { LiquibaseChangelogExtractor.extract(it) }

        matchBundled(dataObjectKey, service, candidates)?.let {
            return Resolved(GeneratedChangelog(dataObjectKey, it.xml), "Extracted from ${it.fileName}")
        }
        val tableName = service?.tableName ?: index.dataObjectTables()[dataObjectKey].orEmpty()
        val xml = LiquibaseChangelogGenerator.synthesize(dataObjectKey, tableName, info.fieldMappings)
        return Resolved(GeneratedChangelog(dataObjectKey, xml), "Synthesized from ${info.fieldMappings.size} field(s)")
    }

    /**
     * The bundled changelog backing a data object, matched (strongest first): the backing service's
     * `referencedLiquibaseModelKey` equals the changelog's key; else a changelog whose `tableName`
     * equals the service's; else the changelog whose key equals the data-object key. Null when none
     * of these line up (→ synthesize).
     */
    private fun matchBundled(
        dataObjectKey: String,
        service: ServiceTable?,
        candidates: List<LiquibaseChangelogExtractor.Extracted>,
    ): LiquibaseChangelogExtractor.Extracted? {
        service?.referencedLiquibaseModelKey?.takeIf { it.isNotBlank() }?.let { lk ->
            candidates.firstOrNull { it.key == lk }?.let { return it }
        }
        service?.tableName?.takeIf { it.isNotBlank() }?.let { tn ->
            candidates.firstOrNull { c -> LiquibaseChangelog.tableNames(c.xml).any { it.equals(tn, ignoreCase = true) } }
                ?.let { return it }
        }
        return candidates.firstOrNull { it.key == dataObjectKey }
    }

    /** All model keys of [type] — the picker's list. Builds the index off the EDT if needed. */
    fun keysOfType(type: ModelType): List<String> =
        project.service<FlowableModelIndexService>().keysOfType(type).map { it.key }.distinct().sorted()

    // ---- helpers ----------------------------------------------------------------------------

    /** Every `.zip`/`.bar` archive under [base], skipping build-output directories. */
    private fun archivesUnder(base: VirtualFile): List<VirtualFile> {
        val out = ArrayList<VirtualFile>()
        VfsUtilCore.iterateChildrenRecursively(
            base,
            { vf -> !(vf.isDirectory && vf.name in ModelPaths.EXCLUDE_DIRS) },
            { file -> if (LiquibaseChangelogExtractor.isArchive(file)) out.add(file); true },
        )
        return out
    }

    private fun projectDirFile(): VirtualFile? {
        val dir = AtlasProjectRootService.getInstance(project).activeProjectDir() ?: return null
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)
    }

    private fun applyOnEdt(base: VirtualFile, changelogs: List<GeneratedChangelog>, after: (List<VirtualFile>) -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            after(writeChangelogs(base, changelogs))
        }
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

        /** The developer-code output folder, relative to the active project directory. */
        const val LIQUIBASE_DIR = "src/main/resources/liquibase"

        /** `<key>.changelog.xml`, with the key reduced to filename-safe characters. */
        fun fileNameFor(key: String): String = key.replace(Regex("[^A-Za-z0-9._-]"), "-") + ".changelog.xml"

        fun getInstance(project: Project): LiquibaseScaffoldService = project.service()
    }
}
